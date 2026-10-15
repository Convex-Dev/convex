package convex.lattice;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Function;

import convex.core.data.ACell;
import convex.core.util.Utils;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Root;
import convex.lattice.cursor.TransactionalRootCursor;

/**
 * Domain operations over a cursor at one path.
 *
 * <p>Lightweight components share a working cursor. Components with an independent
 * publication boundary also retain a destination cursor and publication baseline.
 * State changes and sync travel through cursors; there is no component parent link.
 * The cursor knows nothing about lattices: the component declares the structure of
 * the level it owns, typically by passing its lattice as the {@link Shape} of child
 * cursors, the {@link Merge} of forks, and the {@link Signing} or {@link Stamping}
 * rule of a boundary cursor it wraps.</p>
 *
 * <p>Persistence belongs to the host ({@link RootComponent}) and to components which
 * explicitly own a store for their own data, such as DLFS drives.</p>
 *
 * @param <V> Value type of the wrapped cursor
 */
public abstract class ALatticeComponent<V extends ACell> {

	protected final AForkableCursor<V> cursor;

	/** Creates a component over a cursor. */
	protected ALatticeComponent(AForkableCursor<V> cursor) {
		this.cursor=Objects.requireNonNull(cursor,"Component cursor must not be null");
	}

	/**
	 * Creates a component with private working state and a publication destination.
	 * Children normally share paths in this working state; they need no forks.
	 * Sync publishes one snapshot into the destination without syncing that
	 * destination further. Context is resolved at construction and may subsequently
	 * be overridden on the working cursor. No workers or timers are created.
	 */
	protected ALatticeComponent(ALattice<V> lattice, AForkableCursor<V> destination) {
		this(lattice,destination,Function.identity(),(context,value) -> value);
	}

	/** Internal support for a publication boundary with a different stored type. */
	<P extends ACell> ALatticeComponent(ALattice<V> lattice, AForkableCursor<P> destination,
			Function<P,V> read, BiFunction<LatticeContext,V,P> prepare) {
		var publication=new Publication<>(lattice,destination,read,prepare);
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
		return lattice.merge(cursor.getContext(),own,other);
	}

	/** State allocated only for components with an independent publication boundary. */
	private final class Publication<P extends ACell> {
		private final ALattice<V> lattice;
		private final AForkableCursor<P> destination;
		private final Function<P,V> read;
		private final BiFunction<LatticeContext,V,P> prepare;
		private final Root<V> working;
		private final ReentrantLock syncLock=new ReentrantLock();
		// Accessed only under syncLock, which orders publications and write-back.
		private V baseline;

		Publication(ALattice<V> lattice, AForkableCursor<P> destination,
				Function<P,V> read, BiFunction<LatticeContext,V,P> prepare) {
			this.lattice=Objects.requireNonNull(lattice,"Publication lattice must not be null");
			this.destination=Objects.requireNonNull(destination,"Publication destination must not be null");
			this.read=read;
			this.prepare=prepare;
			baseline=read.apply(destination.get());
			LatticeContext context=destination.getContext();
			// Only sync is specialised. Ordinary atomic updates keep their existing
			// root implementation, with no publication lock on the write path.
			working=destination.supportsExclusiveTransactions()
				?new TransactionalRootCursor<>(baseline,lattice,context) {
					@Override public V sync() { return Publication.this.sync(); }
				}
				:new Root<>(baseline,lattice,context) {
					@Override public V sync() { return Publication.this.sync(); }
				};
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
					prepared=prepare.apply(working.getContext(),candidate);
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

	/** Returns the cursor responsible for all state updates and sync. */
	public AForkableCursor<V> cursor() {
		return cursor;
	}

	/**
	 * Synchronises this component's cursor through its immediate cursor boundary.
	 *
	 * <p>For a forked cursor this merges local changes into the cursor it was forked
	 * from; it does not necessarily publish the complete application root. Call
	 * the containing {@link ALatticeApplication application's} {@code sync()} when
	 * the merged state should cross the host publication boundary.</p>
	 *
	 * <p>Working-state publication stops at its destination. Only a live path into
	 * the host reaches host persistence directly. Application preparation hooks run
	 * through the application's {@code sync()}, not a descendant's cursor sync.
	 * Physical durability remains the host's separate {@link RootComponent#flush()}.</p>
	 */
	public void sync() {
		cursor.sync();
	}

	/**
	 * Navigates from a cursor along a path, declaring the shape of each level from
	 * the corresponding node of a lattice, so that a write through the result can
	 * create absent intermediates with their correct types. A level the lattice does
	 * not describe is navigated untyped. Owner boundaries are not crossed here: an
	 * owner's slot is wrapped by the component owning that level, with
	 * {@link convex.lattice.cursor.SignedCursor#forOwner}.
	 *
	 * @param <T> Value type at the end of the path
	 * @param cursor Cursor to navigate from
	 * @param lattice Lattice describing the value at {@code cursor}, or null for none
	 * @param path Keys to navigate
	 * @return Cursor at the path
	 */
	@SuppressWarnings({"unchecked","rawtypes"})
	protected static <T extends ACell> AForkableCursor<T> resolve(AForkableCursor<?> cursor,
			ALattice<?> lattice, ACell... path) {
		AForkableCursor c=cursor;
		ALattice lat=lattice;
		for (ACell key: path) {
			lat=(lat==null)?null:lat.path(key);
			c=(lat==null)?c.path(key):c.child(key,lat);
		}
		return c;
	}
}
