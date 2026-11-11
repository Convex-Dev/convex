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
import convex.core.util.Utils;
import convex.lattice.cursor.AForkableCursor;

/**
 * A component composed of named regions, at a host root or any nested index.
 *
 * <p>Registered children define this component's interests. Incoming entries and
 * path deliveries go through each child's {@link #mergeAt}; unknown keys and
 * absent entries do nothing. An explicitly present null is delivered to the child.
 * No lattice merge is inferred from a child's shape.</p>
 *
 * <p>This container manages the regions it makes. A factory gives each region either
 * a working copy of its own, by forking the cursor it is given, so that the region
 * edits and transacts in isolation, or a live view that writes straight into the
 * container. {@link #syncChildren()} syncs every region made so far, in registration
 * order, and {@link #sync()} then reconciles the container's own storage with its
 * destination, which does nothing for live storage. Sync is not atomic across
 * regions: earlier work stands if a later region fails.</p>
 *
 * <p>A transaction on one region, through that region's own component, is isolated
 * to it. A transaction on the whole container, by a subclass that implements
 * {@link TransactionalComponent}, spans its regions: the container rebound over the
 * draft makes its own regions there, and they are synced into the draft before it
 * commits. Work still pending in this instance's regions is not part of such a
 * transaction; it merges with what the transaction committed when the region next
 * syncs.</p>
 *
 * <p>A fork of this container, or a container built over a fork, reconciles at sync
 * region by region ({@link #mergePublication(Index,Index,Index)}): a region changed
 * on only one side takes that side, so regions that update independently never
 * conflict, and a region this container does not serve that both sides changed
 * keeps the destination's value.</p>
 *
 * <p>Register children during construction, before any delivery or sync;
 * registration takes no lock. The first merge, path delivery or sync freezes
 * this component's registrations. Explicit children may be shared
 * with other applications: registration neither rebinds them nor changes their
 * policy or lifecycle, and this container never syncs them. Whoever made an
 * explicit child syncs it. Shared children may receive repeated or concurrent
 * deliveries, so their operations must support that use.</p>
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
	 * The factory chooses the region's boundary: a child over a fork of the cursor
	 * (for example {@code new Notes(cursor).fork()}) edits and transacts in isolation
	 * and is published by the container's sync; a child over the cursor itself writes
	 * straight into the container.
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
	 *
	 * <p>This container routes deliveries to the child but never syncs it, so a child
	 * over a fork of its own is published only when whoever made it syncs it. Nor can
	 * the container remake it, so a rebound or forked counterpart refuses it when it
	 * is needed. Use {@link #createRegion} for a region this container should manage.</p>
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
	 * any domain policy explicitly. This container manages the child: its sync syncs
	 * it, and a rebound or forked counterpart makes its own with the same factory.
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

	/**
	 * Freezes registrations, prepares, syncs the regions this container made, then
	 * reconciles its own storage with its destination.
	 */
	@Override
	public Index<Keyword,ACell> sync() {
		regions();
		beforeSync();
		return super.sync();
	}

	/**
	 * Prepares state after registrations freeze and before any region syncs.
	 * Failure prevents the sync. This hook can be called concurrently and
	 * must use thread-safe component APIs. A container that is itself a region also
	 * prepares within each attempt of a transaction on its parent, so the hook must
	 * be safe to repeat and must not depend on the attempt committing.
	 */
	protected void beforeSync() {
	}

	/**
	 * Syncs, in registration order, each region this container made from a factory.
	 * A region not yet made has nothing pending, and one made live has nothing to
	 * bring in. Explicit children are never synced here: they belong to whoever
	 * registered them.
	 */
	@Override
	protected void syncChildren() {
		for (Region region:regions().values()) {
			if (region.factory==null) continue;
			ALatticeComponent<?> made=region.component.get();
			if (made!=null) made.sync();
		}
	}

	/**
	 * Reconciles this container's working copy with a destination that also changed,
	 * key by key. A key changed on only one side, or changed to the same value on
	 * both, takes that value. A key this container does not serve keeps the
	 * destination's value. A region it serves that the two sides changed differently
	 * goes to {@link #mergeRegion}.
	 */
	@Override
	protected Index<Keyword,ACell> mergePublication(Index<Keyword,ACell> base,Index<Keyword,ACell> own,Index<Keyword,ACell> other) {
		Index<Keyword,ACell> result=other;
		for (Index<Keyword,ACell> side:Arrays.asList(base,own)) {
			if (side==null) continue;
			long n=side.count();
			for (long i=0;i<n;i++) {
				// Stored keys are whatever blob-like cells arrived, not necessarily Keywords
				ABlobLike<?> key=((Index<?,?>)side).entryAt(i).getKey();
				if (side==own && base!=null && base.containsKey(key)) continue; // already seen with base
				boolean ownHas=has(own,key);
				ACell ownValue=get(own,key);
				if (same(ownHas,ownValue,has(base,key),get(base,key))) continue;
				boolean otherHas=has(other,key);
				ACell otherValue=get(other,key);
				if (same(otherHas,otherValue,has(base,key),get(base,key)) || same(ownHas,ownValue,otherHas,otherValue)) {
					result=put(result,key,ownHas,ownValue);
				} else {
					Keyword region=served(key);
					if (region!=null) result=put(result,key,true,mergeRegion(region,get(base,key),ownValue,otherValue));
				}
			}
		}
		return result;
	}

	/** Returns the registered key matching a stored key, by the index's blob comparison, or null. */
	private Keyword served(ABlobLike<?> key) {
		for (Keyword registered:regions().keySet()) {
			if (registered.toBlob().equals(key.toBlob())) return registered;
		}
		return null;
	}

	/**
	 * Reconciles one region that this container's working copy and its destination
	 * changed differently. Called within the destination's atomic update, so it must
	 * use only the values given. The default refuses: a region's own merge works on
	 * its component's working type, which may differ from what is stored here, so a
	 * subclass that forks itself while such conflicts can arise supplies the rule.
	 *
	 * @param key Region key
	 * @param base Stored value both sides last agreed on, or null
	 * @param own Stored value in this working copy, or null if absent
	 * @param other Stored value at the destination, or null if absent
	 * @return Reconciled stored value
	 */
	protected ACell mergeRegion(Keyword key,ACell base,ACell own,ACell other) {
		throw new UnsupportedOperationException("Region "+key+" changed both in "+getClass().getSimpleName()
			+" and at its destination, and no region merge is defined");
	}

	private static boolean has(Index<Keyword,ACell> index,ACell key) {
		return (index!=null) && index.containsKey(key);
	}

	private static ACell get(Index<Keyword,ACell> index,ACell key) {
		return (index==null)?null:index.get(key);
	}

	private static boolean same(boolean aHas,ACell a,boolean bHas,ACell b) {
		return (aHas==bHas) && Utils.equals(a,b);
	}

	@SuppressWarnings("unchecked")
	private static Index<Keyword,ACell> put(Index<Keyword,ACell> index,ABlobLike<?> key,boolean present,ACell value) {
		Index<ABlobLike<?>,ACell> entries=(Index<ABlobLike<?>,ACell>)(Index<?,?>)index;
		if (present) entries=((entries==null)?Index.<ABlobLike<?>,ACell>none():entries).assoc(key,value);
		else if (entries!=null) entries=entries.dissoc(key);
		return (Index<Keyword,ACell>)(Index<?,?>)entries;
	}
}
