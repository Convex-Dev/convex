package convex.lattice;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.MapEntry;
import convex.core.data.Path;
import convex.core.store.Persister;
import convex.core.util.MergeFunction;
import convex.core.util.Utils;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.ForkedCursor;
import convex.lattice.cursor.ThreeWayMerge;

/**
 * Domain operations over a cursor at one path.
 *
 * <p>Lightweight components share a working cursor. Components with an independent
 * publication boundary bind a {@link ForkedCursor} of their destination. Sync
 * supplies this component's {@link #reconcile(ACell,ACell,ACell)} to that fork. Representation changes
 * compose through destination cursor views, such as signing or projection.
 * Publication uses ordinary cursor updates; there is no component parent link.
 * The cursor knows nothing about lattices: the component declares the structure of
 * the level it owns, supplying read/write functions for child views and binding
 * the authoring functions of signing and stamping views.
 * Merge and publication policy remain in components: {@link #merge} is a component's
 * own behaviour for an incoming value at its level, delegating to a lattice or not.</p>
 *
 * <p>Subclasses write each operation as one atomic update or a transaction and take
 * no locks; see "Concurrency without locks" in
 * {@code convex-core/docs/LATTICE_APPLICATIONS.md}.</p>
 *
 * <p>No abstract lattice definition is required. Domain components may call lattice
 * rules as implementation details. {@link LatticeComponent} adapts a supplied lattice
 * when its generic merge and navigation are the intended component behaviour.</p>
 *
 * <p>A component never holds a store. One with working state of its own may persist
 * it explicitly with {@link #persist()}, through a function supplied at construction.
 * Whether and when to persist intermediate working state is the application's decision.
 * Sync reconciles into the destination, whose storage behaviour is its own concern.
 * Components which own a store for their own data, such as DLFS drives, manage it
 * themselves.</p>
 *
 * @param <V> Value type of the wrapped cursor
 */
public abstract class ALatticeComponent<V extends ACell> {

	/** Working storage bound at construction; editing views do not move this boundary. */
	protected final AForkableCursor<V> storage;

	/** Optional function used by explicit {@link #persist()} calls for this working state. */
	private final Persister persister;

	/** This component's {@link #reconcile(ACell,ACell,ACell)}, made once rather than at each sync or commit. */
	private final ThreeWayMerge<V> reconciler=this::reconcile;

	/** Binds the supplied storage directly, without creating another fork. */
	protected ALatticeComponent(AForkableCursor<V> cursor) {
		this(cursor,null);
	}

	/** Binds the supplied storage with the function that persists it, for working state of its own. */
	protected ALatticeComponent(AForkableCursor<V> cursor,Persister persister) {
		this.storage=Objects.requireNonNull(cursor,"Component cursor must not be null");
		this.persister=persister;
	}

	/**
	 * Makes this component's counterpart over the supplied working storage: the same
	 * domain operations and configuration on a different cursor. {@link #fork()} and
	 * each attempt of a {@link TransactionalComponent} transaction call it. Bind the
	 * cursor directly, without forking it.
	 *
	 * <p>Only the cursor's value commits or publishes, so the counterpart keeps there
	 * whatever must survive. The component decides what happens to everything else it
	 * holds. Keep a reference to what is only read or is safe to use repeatedly, such
	 * as a clock, signer or store. Give the counterpart its own copy of what it changes;
	 * the copy lives and dies with the counterpart. Make child components and editing
	 * views on the supplied cursor, when they are needed, and never retain those bound
	 * to this instance. Leave out runtime and lifecycle state.</p>
	 *
	 * <p>A transaction may call this several times, and concurrently with other use of
	 * this component, so it must be cheap and free of side effects. The default refuses.
	 * A subclass with further configuration overrides this; one that inherits it gets
	 * a counterpart of its parent class.</p>
	 */
	public ALatticeComponent<V> rebind(AForkableCursor<V> cursor) {
		throw new UnsupportedOperationException(getClass().getSimpleName()+" does not support rebinding");
	}

	/**
	 * Creates independent working state over this component's bound storage. Rebinding
	 * reinstalls local editing policies around the new storage. Subclasses may refine
	 * the return type; publication policy is supplied only when the fork syncs.
	 * This is independent state with reconciliation, not a retrying transaction.
	 * Use {@link TransactionalComponent} when domain operations must be rerun on
	 * concurrent changes rather than selecting or merging competing results.
	 */
	public ALatticeComponent<V> fork() {
		return rebind(storage.fork());
	}

	/**
	 * Resolves a working snapshot that conflicts with a concurrently changed
	 * destination. Called by {@link #reconcile(ACell,ACell,ACell)} only when both sides
	 * changed, within the destination's atomic update. Implementations must use the
	 * supplied values without mutating working state. Both are held values, already
	 * accepted, so implementations check neither. A component that can merge the
	 * destination's changes, such as a CRDT, overrides this; by default a change on both
	 * sides is a conflict.
	 *
	 * @param own Working value
	 * @param other Destination value
	 * @return Reconciled value
	 * @throws MergeConflictException If the two values cannot be reconciled
	 */
	protected V reconcile(V own,V other) {
		throw new MergeConflictException(getClass().getSimpleName()+" cannot reconcile changes on both sides");
	}

	/**
	 * Reconciles this component's working value with its destination, given the value
	 * both sides last agreed on. Called whatever changed: a side that changed nothing
	 * contributes nothing. By default a side that changed nothing takes the other's value, and
	 * {@link #reconcile(ACell,ACell)} resolves changes on both sides. Changes in
	 * different places combine only where the component's value is independent place
	 * by place, as a CRDT's is: an override that combines them must hold to that.
	 *
	 * @param base Value both sides last agreed on, or null if there was none
	 * @param own Working value
	 * @param other Destination value
	 * @return Reconciled value
	 */
	protected V reconcile(V base,V own,V other) {
		if (Utils.equals(own,base)) return other;
		if (Utils.equals(other,base)) return own;
		// Both changed, even to equal values: two increments to the same count are two changes
		return reconcile(own,other);
	}

	/**
	 * Reconciles one entry of a map that both sides changed since the value they last
	 * agreed on, for {@link ALatticeComponent#reconcileEntries}.
	 *
	 * @param <K> Key type
	 * @param <V> Value type
	 */
	@FunctionalInterface
	protected interface EntryReconciler<K extends ACell,V extends ACell> {
		/**
		 * @param key Key of the entry
		 * @param base Entry both sides last agreed on, or null if it was absent
		 * @param own Working entry, or null if absent
		 * @param other Destination entry, or null if absent
		 * @return Entry to keep, or null to leave it absent
		 */
		MapEntry<K,V> reconcile(K key,MapEntry<K,V> base,MapEntry<K,V> own,MapEntry<K,V> other);
	}

	/**
	 * Reconciles maps three-way, entry by entry: an entry changed on only one side takes
	 * that side, removal included, and an entry both sides changed takes what
	 * {@code both} gives. For a {@link #reconcile(ACell,ACell,ACell)} that opts in.
	 *
	 * @param <K> Key type
	 * @param <V> Value type
	 * @param <M> Map type
	 * @param base Map both sides last agreed on, or null if there was none
	 * @param own Working map, or null
	 * @param other Destination map, or null
	 * @param both Reconciles an entry both sides changed
	 * @return Reconciled map
	 */
	@SuppressWarnings("unchecked")
	protected static <K extends ACell,V extends ACell,M extends AMap<K,V>> M reconcileEntries(M base,M own,M other,
			EntryReconciler<K,V> both) {
		M result=other;
		for (K key:changedKeys(base,own)) {
			MapEntry<K,V> was=entry(base,key);
			MapEntry<K,V> mine=entry(own,key);
			if (sameEntry(mine,was)) continue;
			MapEntry<K,V> theirs=entry(other,key);
			MapEntry<K,V> kept=sameEntry(theirs,was)?mine:both.reconcile(key,was,mine,theirs);
			if (kept!=null) {
				AMap<K,V> into=(result!=null)?result:((own!=null)?own:base).empty();
				result=(M)into.assoc(key,kept.getValue());
			} else if (result!=null) {
				result=(M)result.dissoc(key);
			}
		}
		return result;
	}

	/** Keys whose entries may differ between two maps; branches they share are skipped. */
	@SuppressWarnings("unchecked")
	private static <K extends ACell,V extends ACell> List<K> changedKeys(AMap<K,V> base,AMap<K,V> own) {
		List<K> keys=new ArrayList<>();
		if (base==null || own==null) {
			AMap<K,V> side=(base==null)?own:base;
			if (side!=null) {
				long n=side.count();
				for (long i=0;i<n;i++) keys.add(side.entryAt(i).getKey());
			}
			return keys;
		}
		base.mergeDifferences(own,new MergeFunction<V>() {
			@Override public V merge(V a,V b) {
				throw new IllegalStateException("Differences are found by key");
			}
			@Override public V merge(Object key,V a,V b) {
				keys.add((K)key);
				return a;
			}
		});
		return keys;
	}

	private static <K extends ACell,V extends ACell> MapEntry<K,V> entry(AMap<K,V> map,K key) {
		return (map==null)?null:map.getEntry(key);
	}

	/** Whether two entries, either absent, are the same: both absent, or present with equal values. */
	private static boolean sameEntry(MapEntry<?,?> a,MapEntry<?,?> b) {
		if (a==null || b==null) return a==b;
		return Utils.equals(a.getValue(),b.getValue());
	}

	/**
	 * Reconciles values in the form stored at this component's place, for a parent
	 * that reconciles its own value place by place. Called within the parent's atomic
	 * update, so it uses only the values given. By default the stored form is the
	 * working form and this is {@link #reconcile(ACell,ACell,ACell)}; a component
	 * whose storage stores another form overrides this.
	 *
	 * @param base Stored value both sides last agreed on, or null if there was none
	 * @param own Stored working value
	 * @param other Stored destination value
	 * @return Reconciled stored value
	 * @throws MergeConflictException If the two values cannot be reconciled
	 */
	@SuppressWarnings("unchecked")
	protected ACell reconcileStored(ACell base,ACell own,ACell other) {
		return reconcile((V)base,(V)own,(V)other);
	}

	/**
	 * Returns the atomic editing view. Domain operations and children use this
	 * accessor so an override can consistently apply policies such as timestamping.
	 * A subclass can wrap {@code super.cursor()} during construction; constructors
	 * must not invoke an override before its view is initialised. Updating this
	 * cursor does not publish the component.
	 */
	protected AForkableCursor<V> cursor() {
		return storage;
	}

	/**
	 * Returns this component's value, including stored nil; throws when absent.
	 *
	 * <p>This method may be removed from the public API in favour of domain components
	 * providing explicit accessors for the views or fields they choose to expose.</p>
	 */
	public V value() {
		return cursor().get();
	}

	/** Whether this component's location contains a value, including stored nil. */
	public boolean exists() {
		return cursor().exists();
	}

	/**
	 * Resolves external string segments to the component at their destination. The
	 * array is read only during the call. A subclass may override this to narrow its
	 * return type; resolution itself is {@link #resolve(AString[], int, int)}.
	 *
	 * @return Component at the destination, which may currently be absent
	 * @throws NoSuchPathException If a segment names no place
	 * @throws NullPointerException If a segment is null
	 */
	public ALatticeComponent<?> resolve(AString... segments) {
		for (AString segment:segments) Objects.requireNonNull(segment,"Path segment must not be null");
		return resolve(segments,0,segments.length);
	}

	/**
	 * Resolves a path, which keeps its position in its request: a segment that names
	 * no place is reported at its position there. A subclass may override this to
	 * narrow its return type; resolution itself is {@link #resolve(AString[], int, int)}.
	 *
	 * @return Component at the destination, which may currently be absent
	 * @throws NoSuchPathException If a segment names no place
	 */
	public ALatticeComponent<?> resolve(Path path) {
		int start=path.position();
		return resolve(path.request().toArray(new AString[0]),start,start+Utils.checkedInt(path.count()));
	}

	/**
	 * Resolves segments {@code [start,end)} of a caller's request without creating
	 * stored state. This is the method domain components override. An empty range
	 * returns this component. The base defines no external namespace; a domain
	 * component reads the segments it owns and routes the rest to a child as
	 * {@code child.resolve(segments,start+n,end)}, so that {@code start} stays a
	 * position in the request. String interpretation belongs to the selected child,
	 * independently of merge delivery. Children must be bound to this component's
	 * current working view.
	 *
	 * <p>The array is the caller's, lent for the call, and its segments are not
	 * null. A component reads it and never writes it, and copies anything it keeps:
	 * a resolved component keeps only the keys it selects. A segment naming no place
	 * in a component's namespace throws {@link NoSuchPathException}, built with
	 * {@link NoSuchPathException#NoSuchPathException(AString[], int, int)}.</p>
	 *
	 * @param segments Segments of the caller's request
	 * @param start Position of the first segment to resolve
	 * @param end End of the segments to resolve
	 * @return Component at the destination, which may currently be absent
	 * @throws NoSuchPathException If a segment names no place
	 */
	public ALatticeComponent<?> resolve(AString[] segments,int start,int end) {
		Objects.checkFromToIndex(start,end,segments.length);
		if (start==end) return this;
		throw new NoSuchPathException(segments,start,end);
	}

	/**
	 * Returns the bound working storage, before this component's editing policies.
	 * Lifecycle operations use this boundary; ordinary domain edits use {@link #cursor()}.
	 */
	protected final AForkableCursor<V> storage() {
		return storage;
	}

	/**
	 * Merges an incoming value at this component's level into its working state. This
	 * is the component's own behaviour: a lattice offers a default merge for the shape
	 * it declares, and the component delegates to it, in whole or in part, or applies
	 * another rule. Merge is the acceptance boundary for incoming data, so it enforces
	 * any ownership, validity or limits itself, on the incoming value only: held state
	 * was accepted when it arrived and is not checked again. A component that receives
	 * nothing need not define one; the base refuses, so a delivery routed to it fails
	 * rather than vanishing.
	 *
	 * @param other Incoming value at this level
	 * @return Current working value
	 */
	public V merge(V other) {
		throw new UnsupportedOperationException(getClass().getSimpleName()+" defines no merge for incoming values");
	}

	/**
	 * Resolves this component's child component for one key below its level, or null
	 * when it has none there. Unresolved paths go to {@link #mergeAtPath}.
	 * A component with children overrides this to hand a delivery on to them.
	 *
	 * @param <T> Value type at the key
	 * @param key Key below this level
	 * @return Child component, or null
	 */
	protected <T extends ACell> ALatticeComponent<T> child(ACell key) {
		return null;
	}

	/**
	 * Delivers a complete value here, or routes a descendant delivery to a child.
	 * Unresolved paths go to {@link #mergeAtPath}; no value shape is inferred.
	 * Returns current working state, not a publication acknowledgement.
	 */
	@SuppressWarnings("unchecked")
	public V mergeAt(ACell value, ACell... path) {
		if (path.length==0) return merge((V)value);
		ALatticeComponent<ACell> child=child(path[0]);
		if (child!=null) {
			child.mergeAt(value,Arrays.copyOfRange(path,1,path.length));
			return cursor().getOrDefault(null);
		}
		return mergeAtPath(value,path);
	}

	/** Handles a descendant path not claimed by a child. The default rejects unsupported routing. */
	protected V mergeAtPath(ACell value,ACell... path) {
		throw new UnsupportedOperationException(getClass().getSimpleName()+" defines no delivery for this path");
	}

	/**
	 * Brings the children this component manages in working copies of their own into
	 * its working state. {@link #sync()} calls this before reconciling, and a
	 * {@link TransactionalComponent} transaction calls it on each attempt's view
	 * before the draft commits. The default does nothing.
	 *
	 * <p>Almost every component keeps the default. A child over a live path writes
	 * straight into this component's working state, so there is nothing to bring in.
	 * A child with a working copy of its own is normally synced by whoever owns that
	 * copy, never by its parent.</p>
	 *
	 * <p>Override this only to sync children that this component itself made over
	 * forks of its own storage, and whose working copies it has undertaken to bring
	 * in. {@link AKeyedRegions} does so for the regions it makes. Never sync a child
	 * that was handed in, shared or made by someone else: that would publish another
	 * owner's staged work. A component that overrides this owns the boundaries it
	 * creates:</p>
	 * <ul>
	 * <li>a child's edits are not in this component until the child syncs;</li>
	 * <li>a transaction on this component does not see work still pending in this
	 * instance's children: that work merges with whatever the transaction committed
	 * when the child next syncs;</li>
	 * <li>a failure part-way through leaves earlier children synced and later ones
	 * pending.</li>
	 * </ul>
	 *
	 * <p>A transaction may call this several times, once per attempt, each time on a
	 * new view. Each child it syncs there is one made on that view, so the child's
	 * own sync runs within the attempt: its preparation and any additional behaviour
	 * supplied by an override must be safe to repeat and must not depend on the
	 * attempt committing.</p>
	 */
	protected void syncChildren() {
	}

	/** Brings a transaction view's managed children into its draft before the draft commits. */
	static void completeDraft(Object view) {
		if (view instanceof ALatticeComponent<?> component) component.syncChildren();
	}

	/** Returns the bound storage of a transacting component, which must be a lattice component. */
	@SuppressWarnings("unchecked")
	static <V extends ACell> AForkableCursor<V> boundStorage(TransactionalComponent<V,?> source) {
		if (!(source instanceof ALatticeComponent<?> c)) {
			throw new UnsupportedOperationException("Only a lattice component can be transactional: "+source.getClass().getName());
		}
		return ((ALatticeComponent<V>)c).storage;
	}

	/**
	 * Returns the reconciliation of a transacting component, which reconciles a change
	 * made under its claim without passing through it, or null if it is not a lattice
	 * component. A component that cannot reconcile changes on both sides fails that
	 * commit with a {@link MergeConflictException} naming it.
	 */
	@SuppressWarnings("unchecked")
	static <V extends ACell> ThreeWayMerge<V> reconciler(TransactionalComponent<V,?> source) {
		if (!(source instanceof ALatticeComponent<?> c)) return null;
		return ((ALatticeComponent<V>)c).reconciler;
	}

	/**
	 * Syncs the bound storage with this component's reconciliation policy, after
	 * {@link #syncChildren()} has brought in any children it manages. A fork
	 * publishes into its immediate destination; ordinary cursors return their value.
	 * Editing-view overrides cannot hide this boundary, and descendant views never
	 * publish enclosing storage. No host persistence is implied.
	 *
	 * <p>The base implementation does not persist working state or call the supplied
	 * persistence function. The destination owns its update behaviour, which may
	 * include signing or storage writes. Applications choose when to call
	 * {@link #persist()} separately; subclasses may add persistence to their sync
	 * policy, with their own completion and failure semantics.</p>
	 *
	 * @return Published value
	 */
	public V sync() {
		syncChildren();
		return storage.reconcile(reconciler);
	}

	/**
	 * Persists this component's working state through the function supplied at
	 * construction, without publishing it. Its storage replaces the working value
	 * with the persisted equal in one atomic update
	 * ({@link convex.lattice.cursor.ACursor#persist}), and a fork persists its
	 * baseline too, so the cells need not stay in memory. Nothing is
	 * signed and no destination changes. Persisting alone supplies no retention or
	 * durability guarantee. A component without a persistence function refuses.
	 *
	 * @return Persisted working value
	 * @throws IOException If the store fails
	 */
	public V persist() throws IOException {
		if (persister==null) throw new UnsupportedOperationException(getClass().getSimpleName()+" does not know how to persist");
		return storage.persist(persister);
	}

}
