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
import convex.lattice.cursor.BoundaryCursor;

/**
 * A component composed of named regions, at a host root or any nested index.
 *
 * <p>Registered children, other than viewed ones, define this component's interests.
 * Incoming entries and path deliveries go through each such child's {@link #mergeAt};
 * unknown and viewed keys and absent entries do nothing. An explicitly present null is delivered to the child.
 * No lattice merge is inferred from a child's shape.</p>
 *
 * <p>This container manages the regions it makes. Each region's factory is given a
 * {@link BoundaryCursor} over the region's place in the container: writes through
 * it land in the container at once, and claims stop at it. A factory may give its
 * region a working copy of its own by forking that cursor.
 * {@link #syncChildren()} syncs every region made so far, in registration order,
 * and {@link #sync()} then reconciles the container's own storage with its
 * destination, which does nothing for live storage. Sync is not atomic across
 * regions: earlier work stands if a later region fails.</p>
 *
 * <p>A live region writes through its supplied boundary cursor into the container.
 * A transaction on that region commits into the container at the region's place,
 * and an exclusive one claims only that region's boundary, so regions transact
 * exclusively at the same time. A transaction
 * on the whole container, by a subclass that implements {@link TransactionalComponent},
 * spans its regions and works on their values published into the container. A region
 * write landing there during an attempt merges with its run region by region
 * ({@link #reconcile(Index,Index,Index)}); a region both changed whose component
 * cannot reconcile it reruns it under the claim. An exclusive
 * transaction on the container holds the claim beneath every region's boundary, so region writes wait
 * for it. Work a region keeps in a working copy of its own, including transactions
 * committed into that fork, has not yet been published into the container. It is
 * not part of a transaction on the container, and it merges with the container's
 * current value when the region next syncs. A live region's exclusive transaction,
 * and an ordinary one once it has taken the
 * claim after a conflict, holds against writes through its boundary; a
 * transaction on the container that changes the same region meanwhile does not pass
 * through it, so the region's commit reconciles that change with its own run by the
 * region's {@link #reconcile(Index,Index,Index)}, or fails if the region cannot.</p>
 *
 * <p>A fork of this container, or a container built over a fork, reconciles at sync
 * region by region ({@link #reconcile(Index,Index,Index)}): a region changed
 * on only one side takes that side, so regions that update independently never
 * conflict. A region both sides changed is reconciled by its own component, which
 * may refuse. A key this container does not serve that both sides changed keeps
 * the destination's value.</p>
 *
 * <p>Register children during construction, before any delivery or sync;
 * registration takes no lock. The first merge, path delivery or sync freezes
 * this component's registrations. Explicit children may be shared
 * with other applications: registration neither rebinds them nor changes their
 * policy or lifecycle, and this container never syncs them. Whoever made an
 * explicit child syncs it. Shared children may receive repeated or concurrent
 * deliveries, so their operations must support that use.</p>
 *
 * <p>A region that another application on the same host merges can be viewed rather
 * than created ({@link #viewRegion}). A viewed region is made, synced, rebound and
 * reconciled as a created one, so this container's forks and transactions can use its
 * domain operations, but incoming values are not delivered to it: the application
 * that merges the region takes them for the host. A host therefore has one merging
 * inclusion of each shared region. Where both sides changed a viewed region, its
 * component reconciles it; one that supports a three-way reconcile keeps the merging
 * application's changes, removals included, beside this container's own.</p>
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
	 * atomic writes may retry it. Construction and reads never call it. Every write
	 * through a region cursor calls it while the index is absent, including one that
	 * changes nothing, such as an unchanged region's sync, and is refused if it throws.
	 * Explicitly registered children retain their own storage and creation policy.
	 */
	protected Index<Keyword,ACell> createContainer() { return Index.none(); }

	/**
	 * Constructs a child with its region cursor, a {@link BoundaryCursor} over the
	 * region's place in the container. The stored value type can differ from the
	 * child's working type, as for signed components. A child over the cursor itself
	 * commits straight into the container; a child over a fork of it (for example
	 * {@code new Notes(cursor).fork()}) keeps its work until the container's sync
	 * publishes it.
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
		/** Whether incoming values are delivered to the child; false for a viewed region. */
		private final boolean delivered;
		private final AtomicReference<ALatticeComponent<?>> component=new AtomicReference<>();

		Region(Keyword key,ALatticeComponent<?> explicit) {
			this(key,null,null,true);
			component.set(explicit);
		}

		Region(Keyword key,RegionFactory<?,?> factory,boolean delivered) {
			this(key,factory,null,delivered);
		}

		/** The same region of another container, with no child made yet. */
		Region(Region source) {
			this(source.key,source.factory,
				source.component.get() instanceof AKeyedRegions container?container:source.template,source.delivered);
		}

		private Region(Keyword key,RegionFactory<?,?> factory,AKeyedRegions template,boolean delivered) {
			this.key=key;
			this.factory=factory;
			this.template=template;
			this.delivered=delivered;
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

		/** Reconciles this region's stored values, changed on both sides, by its child. */
		ACell reconcile(ACell base,ACell own,ACell other) {
			if (factory==null && component.get()==null) {
				throw new MergeConflictException("Region "+key+" changed on both sides, and its explicit child is not here to reconcile it");
			}
			return child().reconcileStored(base,own,other);
		}

		@SuppressWarnings("unchecked")
		private ALatticeComponent<?> create() {
			var entries=new IndexComponent<Keyword,ACell>(cursor()) {
				@Override protected Index<Keyword,ACell> createContainer() {
					return AKeyedRegions.this.createContainer();
				}
			};
			ALatticeComponent<?> created=((RegionFactory<ACell,?>)factory).create(BoundaryCursor.over(entries.entry(key)));
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
	protected final <V extends ACell,C extends ALatticeComponent<?>> C createRegion(
			Keyword key,RegionFactory<V,C> factory) {
		return makeRegion(key,factory,true);
	}

	/**
	 * Constructs and registers a child viewing a region that another application on
	 * the host merges. The child is made, synced, rebound and reconciled as one made
	 * with {@link #createRegion}, but no incoming value is delivered to it.
	 */
	protected final <V extends ACell,C extends ALatticeComponent<?>> C viewRegion(
			Keyword key,RegionFactory<V,C> factory) {
		return makeRegion(key,factory,false);
	}

	@SuppressWarnings("unchecked")
	private <V extends ACell,C extends ALatticeComponent<?>> C makeRegion(
			Keyword key,RegionFactory<V,C> factory,boolean delivered) {
		checkRegistration(key);
		Objects.requireNonNull(factory,"Region factory must not be null");
		Region region=new Region(key,factory,delivered);
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
				Region region=entry.getValue();
				if (region.delivered && incoming.containsKey(key)) region.child().mergeAt(incoming.get(key));
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
			if (entry.getKey().toBlob().equals(blob.toBlob())) {
				Region region=entry.getValue();
				return region.delivered?(ALatticeComponent<V>)region.child():null;
			}
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
	 * key by key. A key changed on only one side takes that side's value. A key this
	 * container does not serve keeps the destination's value. A region it serves that
	 * both sides changed, even to equal values, is reconciled by the region's component
	 * ({@link ALatticeComponent#reconcileStored}); a region whose explicit child this
	 * container cannot make is a conflict.
	 */
	@Override
	protected Index<Keyword,ACell> reconcile(Index<Keyword,ACell> base,Index<Keyword,ACell> own,Index<Keyword,ACell> other) {
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
				if (same(otherHas,otherValue,has(base,key),get(base,key))) {
					result=put(result,key,ownHas,ownValue);
				} else {
					Region region=served(key);
					if (region!=null) result=put(result,key,true,region.reconcile(get(base,key),ownValue,otherValue));
				}
			}
		}
		return result;
	}

	/** Returns the region matching a stored key, by the index's blob comparison, or null. */
	private Region served(ABlobLike<?> key) {
		for (Map.Entry<Keyword,Region> entry:regions().entrySet()) {
			if (entry.getKey().toBlob().equals(key.toBlob())) return entry.getValue();
		}
		return null;
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
