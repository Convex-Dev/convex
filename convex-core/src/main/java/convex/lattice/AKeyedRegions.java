package convex.lattice;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import convex.core.data.ABlobLike;
import convex.core.data.ACell;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.lattice.cursor.AForkableCursor;

/**
 * A component composed of named regions, at a host root or any nested index.
 *
 * <p>Registered children define this component's interests. Incoming entries and
 * path deliveries go through each child's {@link #mergeAt}; unknown keys and
 * absent entries do nothing. An explicitly present null is delivered to the child.
 * No lattice merge is inferred from a child's shape.</p>
 *
 * <p>This component has no private container snapshot. Its cursor shows the
 * destination; pending edits belong to its staged children. Sync publishes each
 * child at its own boundary, in registration order, without subsequently writing
 * the container. Unregistered regions are therefore preserved even when another
 * application changes them. Merge and sync are not atomic across children:
 * earlier successful work stands if a later child fails.</p>
 *
 * <p>A supplied fork remains private working storage: syncing this container
 * publishes children into it, but does not publish the fork itself. Its owner
 * must explicitly publish the aggregate with the appropriate merge policy.</p>
 *
 * <p>Register children during construction, before any delivery or sync;
 * registration takes no lock. The first merge, path delivery or sync freezes
 * this component's registrations. Explicit children may be shared
 * with other applications: registration neither rebinds them nor changes their
 * policy or lifecycle. Shared children may receive repeated or concurrent
 * deliveries and syncs, so their operations must support that use.</p>
 *
 * <p>A rebound container keeps its factories but none of the children made by them.
 * It makes each child on its own cursor the first time that child is needed, so a
 * factory must only construct, and only from the cursor it is given. Regions added
 * to a nested container after its factory ran carry over too. An explicit child has
 * no factory and nothing says how it was bound, so a rebound container refuses it
 * when it is needed.</p>
 *
 * <p>An application made of regions can extend this component or hold one; see
 * {@code convex-core/docs/LATTICE_APPLICATIONS.md} for the choice.</p>
 */
public abstract class AKeyedRegions extends ALatticeComponent<Index<Keyword,ACell>> {

	private final Map<Keyword,Region> registrations=new LinkedHashMap<>();
	private volatile Map<Keyword,Region> regions;

	/** Constructs a container view without changing or publishing its destination. */
	protected AKeyedRegions(AForkableCursor<Index<Keyword,ACell>> cursor) {
		super(cursor);
	}

	/**
	 * Binds a container to new working storage with a source's regions, for a
	 * {@link #rebind}. No child is made until it is needed.
	 */
	protected AKeyedRegions(AForkableCursor<Index<Keyword,ACell>> cursor,AKeyedRegions source) {
		super(cursor);
		adoptRegions(source);
	}

	/**
	 * Creates an absent containing index for a changed write through a constructed
	 * child's region cursor. Return a non-null index, optionally with initial entries,
	 * or throw to require explicit initialisation. This hook must be pure because
	 * atomic writes may retry it. Construction, reads and unchanged writes never call
	 * it. Explicitly registered children retain their own storage and creation policy.
	 */
	protected Index<Keyword,ACell> createContainer() { return Index.none(); }

	/**
	 * Constructs a child with its region cursor. The stored
	 * value type can differ from the child's working type, as for signed components.
	 */
	@FunctionalInterface
	public interface RegionFactory<V extends ACell,C extends ALatticeComponent<?>> {
		C create(AForkableCursor<V> cursor);
	}

	/** One registered child, and how this container makes it if it has none yet. */
	private final class Region {
		private final Keyword key;
		/** Makes the child on this container's cursor; null for an explicit child. */
		private final RegionFactory<?,?> factory;
		/** A container whose regions a child made here also takes, or null. */
		private final AKeyedRegions template;
		private final AtomicReference<ALatticeComponent<?>> component=new AtomicReference<>();

		Region(Keyword key,ALatticeComponent<?> explicit) {
			this(key,null,null);
			component.set(explicit);
		}

		Region(Keyword key,RegionFactory<?,?> factory) {
			this(key,factory,null);
		}

		/** The same region of another container, with no child made yet. */
		Region(Region source) {
			this(source.key,source.factory,
				source.component.get() instanceof AKeyedRegions container?container:source.template);
		}

		private Region(Keyword key,RegionFactory<?,?> factory,AKeyedRegions template) {
			this.key=key;
			this.factory=factory;
			this.template=template;
		}

		/** Returns the child, making it on this container's cursor at the first need. */
		ALatticeComponent<?> child() {
			ALatticeComponent<?> made=component.get();
			if (made!=null) return made;
			if (factory==null) {
				throw new UnsupportedOperationException("Region "+key+" holds an explicit child, which cannot be made on new storage");
			}
			ALatticeComponent<?> created=create();
			// A factory only constructs, so the loser of a race is simply dropped
			ALatticeComponent<?> winner=component.compareAndExchange(null,created);
			return (winner==null)?created:winner;
		}

		@SuppressWarnings("unchecked")
		private ALatticeComponent<?> create() {
			var entries=new IndexComponent<Keyword,ACell>(cursor()) {
				@Override protected Index<Keyword,ACell> createContainer() {
					return AKeyedRegions.this.createContainer();
				}
			};
			ALatticeComponent<?> created=((RegionFactory<ACell,?>)factory).create(entries.path(key));
			Objects.requireNonNull(created,"Region component must not be null");
			if (template!=null && created instanceof AKeyedRegions container) container.adoptRegions(template);
			return created;
		}
	}

	private void adoptRegions(AKeyedRegions source) {
		for (Region region:source.registrations.values()) registrations.putIfAbsent(region.key,new Region(region));
	}

	/**
	 * Registers an existing child, retaining the exact instance and its policy.
	 * The caller must supply a component bound to this region's intended destination;
	 * cursors do not expose enough binding information to verify this generally.
	 * Registration does not relocate the component or take exclusive ownership.
	 */
	protected final <C extends ALatticeComponent<?>> C region(Keyword key,C component) {
		checkRegistration(key);
		Objects.requireNonNull(component,"Region component must not be null");
		registrations.put(key,new Region(key,component));
		return component;
	}

	/**
	 * Constructs and registers a child at this key. The child's region cursor is
	 * bound through {@link #cursor()} when this runs, so its edits apply this
	 * component's editing policy; a subclass that overrides {@code cursor()}
	 * initialises its view before creating regions. A missing containing index is
	 * created through {@link #createContainer()} on the first changed write, never on
	 * construction or read. Access rejects incompatible containers. The factory binds
	 * any domain policy explicitly.
	 */
	@SuppressWarnings("unchecked")
	protected final <V extends ACell,C extends ALatticeComponent<?>> C createRegion(
			Keyword key,RegionFactory<V,C> factory) {
		checkRegistration(key);
		Objects.requireNonNull(factory,"Region factory must not be null");
		Region region=new Region(key,factory);
		C child=(C)region.child();
		checkRegistration(key);
		registrations.put(key,region);
		return child;
	}

	/** Returns the child registered at a key, making it at the first need. */
	@SuppressWarnings("unchecked")
	protected final <C extends ALatticeComponent<?>> C registered(Keyword key) {
		return (C)registrations.get(key).child();
	}

	private void checkRegistration(Keyword key) {
		Objects.requireNonNull(key,"Region key must not be null");
		if (regions!=null) throw new IllegalStateException("Region registrations are frozen");
		if (registrations.containsKey(key)) throw new IllegalArgumentException("Duplicate region: "+key);
	}

	private Map<Keyword,Region> regions() {
		Map<Keyword,Region> result=regions;
		if (result==null) {
			// Registration has finished, so threads that freeze together build equal maps
			result=Collections.unmodifiableMap(new LinkedHashMap<>(registrations));
			regions=result;
		}
		return result;
	}

	/** Refused: a container has no working state of its own to fork, as its children do. */
	@Override
	public ALatticeComponent<Index<Keyword,ACell>> fork() {
		throw new UnsupportedOperationException(getClass().getSimpleName()+" has no working state of its own to fork");
	}

	/** Delivers present registered entries, without publishing staged children. */
	@Override
	public Index<Keyword,ACell> merge(Index<Keyword,ACell> incoming) {
		var selected=regions();
		if (incoming!=null) {
			for (var entry:selected.entrySet()) {
				Keyword key=entry.getKey();
				if (incoming.containsKey(key)) entry.getValue().child().mergeAt(incoming.get(key));
			}
		}
		return cursor().getOrDefault(null);
	}

	/** Resolves keys with the same blob comparison as the underlying index. */
	@SuppressWarnings("unchecked")
	@Override
	protected <V extends ACell> ALatticeComponent<V> child(ACell key) {
		var selected=regions();
		if (!(key instanceof ABlobLike<?> blob)) return null;
		for (var entry:selected.entrySet()) {
			if (entry.getKey().toBlob().equals(blob.toBlob())) return (ALatticeComponent<V>)entry.getValue().child();
		}
		return null;
	}

	/** Routes through registered components only; an unknown path is a no-op. */
	@Override
	public Index<Keyword,ACell> mergeAt(ACell value,ACell... path) {
		if (path.length==0) return super.mergeAt(value,path);
		ALatticeComponent<?> component=child(path[0]);
		if (component!=null) component.mergeAt(value,Arrays.copyOfRange(path,1,path.length));
		return cursor().getOrDefault(null);
	}

	/** Freezes registrations, prepares and publishes children, then reads the container. */
	@Override
	public Index<Keyword,ACell> sync() {
		regions();
		beforeSync();
		syncChildren();
		return storage.getOrDefault(null);
	}

	/**
	 * Prepares state after registrations freeze and before any child publishes.
	 * Failure prevents child publication. This hook can be called concurrently and
	 * must use thread-safe component APIs.
	 */
	protected void beforeSync() {
	}

	/**
	 * Publishes children in registration order; one not yet made has nothing staged.
	 * The container has no staged snapshot.
	 */
	protected void syncChildren() {
		for (Region region:regions().values()) {
			ALatticeComponent<?> made=region.component.get();
			if (made!=null) made.sync();
		}
	}
}
