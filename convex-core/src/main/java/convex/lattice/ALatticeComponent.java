package convex.lattice;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Function;

import convex.core.data.ACell;
import convex.core.data.SignedData;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.lattice.cursor.ACursor;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.SignedCursor;
import convex.lattice.cursor.ProjectionCursor;
import convex.lattice.cursor.Root;
import convex.lattice.cursor.TransactionalRootCursor;
import convex.lattice.generic.AOwnerLattice;
import convex.lattice.generic.SignedLattice;

/**
 * Domain operations over a cursor at one path.
 *
 * <p>Lightweight components share a working cursor. Components with an independent
 * publication boundary also retain a destination cursor and publication baseline.
 * Publication uses ordinary cursor updates; there is no component parent link.
 * The cursor knows nothing about lattices: the component declares the structure of
 * the level it owns, supplying read/write functions for child views and binding
 * the authoring functions of signing and stamping views.
 * Merge and publication policy remain in components.</p>
 *
 * <p>Persistence belongs to the host ({@link LatticeHost}) and to components which
 * explicitly own a store for their own data, such as DLFS drives.</p>
 *
 * @param <V> Value type of the wrapped cursor
 */
public abstract class ALatticeComponent<V extends ACell> {

	protected final AForkableCursor<V> cursor;
	private volatile LatticeContext context=LatticeContext.EMPTY;
	private final Publication<?> publication;

	/** Returns this component's write and merge policy. */
	public final LatticeContext getContext() { return context; }

	/** Replaces policy for this component. Existing children retain their resolved policy. */
	public final void setContext(LatticeContext context) {
		this.context=(context==null)?LatticeContext.EMPTY:context;
	}

	/** Resolves policy for a newly constructed child or rebound view, without retaining a parent. */
	protected final <C extends ALatticeComponent<?>> C configured(C child) {
		child.setContext(getContext());
		return child;
	}

	/** Creates independent working state with component-owned CAS-or-merge publication. */
	public static <T extends ACell> ALatticeComponent<T> working(ALattice<T> lattice,
			ACursor<T> destination, LatticeContext context) {
		ALatticeComponent<T> component=new ALatticeComponent<T>(lattice,destination) {};
		component.setContext(context);
		return component;
	}

	/** Immediate signing boundary with an explicitly bound authoring function. */
	protected final <T extends ACell> SignedCursor<T> signedCursor(
			ACursor<SignedData<T>> slot,
			AOwnerLattice<?,T,?> owners, ACell owner) {
		SignedLattice<T> rule=owners.signingFor(owner);
		if (rule==null) throw new IllegalArgumentException("Invalid owner: "+owner);
		LatticeContext policy=getContext();
		return SignedCursor.create(slot,v -> rule.sign(policy,v));
	}

	/** Creates a component over a cursor. */
	protected ALatticeComponent(AForkableCursor<V> cursor) {
		this.cursor=Objects.requireNonNull(cursor,"Component cursor must not be null");
		publication=null;
	}

	/**
	 * Creates a component with private working state and a publication destination.
	 * Children normally share paths in this working state; they need no forks.
	 * Sync publishes one snapshot into the destination without syncing that
	 * destination further. Write and merge policy belongs to this component. No workers or timers are created.
	 */
	protected ALatticeComponent(ALattice<V> lattice, ACursor<V> destination) {
		this(lattice,destination,Function.identity(),(context,value) -> value);
	}

	/** Creates a publication boundary whose destination has a different stored type. */
	protected <P extends ACell> ALatticeComponent(ALattice<V> lattice, ACursor<P> destination,
			Function<P,V> read, BiFunction<LatticeContext,V,P> prepare) {
		publication=new Publication<>(lattice,destination,read,prepare);
		cursor=publication.working;
	}

	/**
	 * Reconciles a publication snapshot with a changed destination. The local edit
	 * is own, including on timestamp ties. An unchanged local value adopts the
	 * destination. Applications may override this to delegate subtrees to their
	 * components, including signing a combined payload. Overrides must use only
	 * the supplied values and must not mutate live working cursors or publish.
	 *
	 * <p>Runs outside atomic update callbacks, once per distinct destination value.
	 * On retry, own is the previous prepared result and baseline is the destination
	 * it was reconciled against. This permits unchanged signatures to be reused.</p>
	 */
	protected V mergePublication(ALattice<V> lattice,V baseline,V own,V other) {
		if (Utils.equals(own,baseline)) return other;
		if (Utils.equals(other,baseline) || Utils.equals(own,other)) return own;
		return lattice.merge(getContext(),own,other);
	}

	/** State allocated only for components with an independent publication boundary. */
	private final class Publication<P extends ACell> {
		private final ALattice<V> lattice;
		private final ACursor<P> destination;
		private final Function<P,V> read;
		private final BiFunction<LatticeContext,V,P> prepare;
		private final Root<V> working;
		private final ReentrantLock syncLock=new ReentrantLock();
		// Accessed only under syncLock, which orders publications and write-back.
		private V baseline;

		Publication(ALattice<V> lattice, ACursor<P> destination,
				Function<P,V> read, BiFunction<LatticeContext,V,P> prepare) {
			this.lattice=Objects.requireNonNull(lattice,"Publication lattice must not be null");
			this.destination=Objects.requireNonNull(destination,"Publication destination must not be null");
			this.read=read;
			this.prepare=prepare;
			baseline=read.apply(destination.get());
			// Working storage has no publication callbacks or component back pointers.
			working=destination.supportsExclusiveTransactions()
				?new TransactionalRootCursor<>(baseline):new Root<>(baseline);
		}

		private V sync() {
			syncLock.lock();
			try {
				V snapshot=working.get();
				V published=publish(snapshot);
				V expected=snapshot;
				V candidate=published;
				while (!working.compareAndSet(expected,candidate)) {
					V current=working.get();
					// New local edits are own. Preparation is outside the CAS, also
					// when reconciliation needs a signature for pending working state.
					candidate=mergePublication(lattice,expected,current,candidate);
					if (Utils.equals(current,candidate)) break;
					expected=current;
				}
				return published;
			} finally {
				syncLock.unlock();
			}
		}

		private V publish(V snapshot) {
			P observed=destination.get();
			V currentValue=read.apply(observed);
			V candidate=mergePublication(lattice,baseline,snapshot,currentValue);
			V preparedValue=null;
			P prepared=null;
			boolean hasPrepared=false;
			while (true) {
				// A no-op can complete at the observation without changing identities.
				if (Utils.equals(currentValue,candidate)) {
					baseline=currentValue;
					return currentValue;
				}
				if (!hasPrepared || !Utils.equals(candidate,preparedValue)) {
					prepared=prepare.apply(getContext(),candidate);
					preparedValue=candidate;
					hasPrepared=true;
				}
				if (destination.compareAndSet(observed,prepared)) {
					baseline=candidate;
					return candidate;
				}
				P current=destination.get();
				V nextValue=read.apply(current);
				// An unrelated ancestor write or equal replacement needs no new merge.
				if (!Utils.equals(nextValue,currentValue)) {
					candidate=mergePublication(lattice,currentValue,candidate,nextValue);
				}
				observed=current;
				currentValue=nextValue;
			}
		}
	}

	/** Returns atomic working storage. Updating or syncing it does not publish this component. */
	public AForkableCursor<V> cursor() {
		return cursor;
	}

	/**
	 * Publishes one working snapshot into this component's destination using
	 * CAS-or-merge. Publication stops at that cursor: its sync is never invoked.
	 * A lightweight view has no independent boundary and simply returns its value;
	 * its edits are already visible in the containing working state.
	 *
	 * <p>Call the enclosing application's sync to run its preparation and publication,
	 * and the host's sync for persistence or replication. A raw cursor fork supplied
	 * to a view remains caller-owned and is not implicitly synced by the component.</p>
	 *
	 * @return Published snapshot, or current value for a lightweight view. Later
	 *         concurrent edits may remain pending in working storage.
	 */
	public V sync() {
		return publication==null?cursor.get():publication.sync();
	}

	/**
	 * Builds child views with component-supplied read/write functions. Each write
	 * function uses the corresponding lattice to construct an absent container
	 * within the atomic update. Reads and view construction never mutate state.
	 * A missing container at an unknown level causes the write to fail. Owner
	 * boundaries are not crossed here: an owner's slot is wrapped by its component with
	 * an explicitly bound signing function.
	 *
	 * @param <T> Value type at the end of the path
	 * @param cursor Cursor to navigate from
	 * @param lattice Lattice describing the value at {@code cursor}, or null for none
	 * @param path Keys to navigate
	 * @return Cursor at the path
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> AForkableCursor<T> resolve(AForkableCursor<?> cursor,
			ALattice<?> lattice, ACell... path) {
		AForkableCursor<ACell> c=(AForkableCursor<ACell>)cursor;
		ALattice<?> lat=lattice;
		for (ACell key: path) {
			final ALattice<?> parentLattice=lat;
			final ACell[] step={key};
			c=ProjectionCursor.create(c,
				value -> RT.getIn(value,step),
				(value,child) -> Utils.equals(RT.getIn(value,step),child)
					?value:LatticeOps.assocIn(value,child,parentLattice,step));
			lat=(lat==null)?null:lat.path(key);
		}
		return (AForkableCursor<T>)c;
	}
}
