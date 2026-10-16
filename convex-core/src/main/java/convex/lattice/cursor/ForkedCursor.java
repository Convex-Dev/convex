package convex.lattice.cursor;

import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.util.Utils;

/**
 * An isolated working copy of a destination cursor, merged by a plug-in function
 * when it publishes.
 *
 * <p>Edits apply to private working storage and are invisible at the destination
 * until {@link #sync()}. Sync publishes one working snapshot by compare-and-set and
 * retries while the destination moves. The fork tracks the destination value the
 * working copy was last reconciled against: an unchanged working copy adopts the
 * destination, an unchanged destination keeps the working snapshot, and only when
 * both have changed does the supplied merge run, with the working snapshot as own.
 * Edits that land in the working copy during a sync stay pending for the next one.
 * Publication stops at the destination and never publishes that cursor further.</p>
 *
 * <p>The fork knows no lattice, context or signing policy: the functions it is
 * given carry whatever policy their owner closed over. A type-changing destination,
 * such as a signed envelope, supplies {@code read} and {@code prepare} functions.
 * Preparation runs once per distinct candidate, outside the atomic compare-and-set,
 * so an unrelated concurrent write reuses a prepared value such as a signature.</p>
 *
 * <p>Working storage supports exclusive transactions when the destination does.
 * That lock is the working copy's own and excludes nothing at the destination.</p>
 *
 * @param <V> Working value type
 */
public final class ForkedCursor<V extends ACell> extends AForkableCursor<V> {

	private final Target<V,?> target;
	private final BinaryOperator<V> merge;
	private final Root<V> working;
	private final ReentrantLock syncLock=new ReentrantLock();

	private <P extends ACell> ForkedCursor(ACursor<P> destination,Function<P,V> read,
			Function<V,P> prepare,BinaryOperator<V> merge) {
		Target<V,P> t=new Target<>(destination,read,prepare);
		this.target=t;
		this.merge=merge;
		this.working=destination.supportsExclusiveTransactions()
			?new TransactionalRootCursor<>(t.baseline):new Root<>(t.baseline);
	}

	/**
	 * Creates a working copy over a destination of the same type.
	 *
	 * @param <V> Value type
	 * @param destination Cursor the copy publishes into
	 * @param merge Merge of own and other, applied only when both have changed
	 * @return Working copy initialised to the destination's current value
	 */
	public static <V extends ACell> ForkedCursor<V> create(ACursor<V> destination,BinaryOperator<V> merge) {
		return create(destination,Function.identity(),Function.identity(),merge);
	}

	/**
	 * Creates a working copy over a destination with a different stored type.
	 *
	 * @param <V> Working value type
	 * @param <P> Stored value type at the destination
	 * @param destination Cursor the copy publishes into
	 * @param read Projection from a stored value to a working value
	 * @param prepare Authoring of a stored value from a working value, such as signing
	 * @param merge Merge of own and other, applied only when both have changed
	 * @return Working copy initialised to the destination's current value
	 */
	public static <V extends ACell,P extends ACell> ForkedCursor<V> create(ACursor<P> destination,
			Function<P,V> read,Function<V,P> prepare,BinaryOperator<V> merge) {
		return new ForkedCursor<>(
			Objects.requireNonNull(destination,"Destination must not be null"),
			Objects.requireNonNull(read,"Read function must not be null"),
			Objects.requireNonNull(prepare,"Prepare function must not be null"),
			Objects.requireNonNull(merge,"Merge function must not be null"));
	}

	/**
	 * Publishes the current working snapshot into the destination.
	 *
	 * <p>The merge runs outside atomic update callbacks, once per distinct
	 * destination value and only when both sides changed. The returned value is
	 * exactly what was published; working edits made during the sync remain
	 * pending.</p>
	 *
	 * @return Published value
	 */
	public V sync() {
		syncLock.lock();
		try {
			V snapshot=working.get();
			V published=target.publish(snapshot,merge);
			V expected=snapshot;
			V candidate=published;
			while (!working.compareAndSet(expected,candidate)) {
				V current=working.get();
				// New working edits are own; the published value is what they reconcile with.
				candidate=reconcile(merge,expected,current,candidate);
				if (Utils.equals(current,candidate)) break;
				expected=current;
			}
			return published;
		} finally {
			syncLock.unlock();
		}
	}

	/**
	 * Decides whether a merge is needed. An unchanged own adopts other; an unchanged
	 * other, or equal values, keep own; otherwise the merge decides.
	 */
	private static <V extends ACell> V reconcile(BinaryOperator<V> merge,V baseline,V own,V other) {
		if (Utils.equals(own,baseline)) return other;
		if (Utils.equals(other,baseline) || Utils.equals(own,other)) return own;
		return merge.apply(own,other);
	}

	/** The destination side of a fork, holding the stored-type functions and the baseline. */
	private static final class Target<V extends ACell,P extends ACell> {
		private final ACursor<P> destination;
		private final Function<P,V> read;
		private final Function<V,P> prepare;
		/** Destination value the working copy was last reconciled against; under the sync lock. */
		private V baseline;

		Target(ACursor<P> destination,Function<P,V> read,Function<V,P> prepare) {
			this.destination=destination;
			this.read=read;
			this.prepare=prepare;
			this.baseline=read.apply(destination.get());
		}

		V publish(V snapshot,BinaryOperator<V> merge) {
			P observed=destination.get();
			V currentValue=read.apply(observed);
			V candidate=reconcile(merge,baseline,snapshot,currentValue);
			V preparedValue=null;
			P prepared=null;
			boolean hasPrepared=false;
			while (true) {
				// A no-op completes at the observation without changing identities.
				if (Utils.equals(currentValue,candidate)) {
					baseline=currentValue;
					return currentValue;
				}
				if (!hasPrepared || !Utils.equals(candidate,preparedValue)) {
					prepared=prepare.apply(candidate);
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
					candidate=reconcile(merge,currentValue,candidate,nextValue);
				}
				observed=current;
				currentValue=nextValue;
			}
		}
	}

	// ===== Working storage =====

	@Override
	protected Lock exclusiveLock() {
		return working.exclusiveLock();
	}

	@Override
	public V get() {
		return working.get();
	}

	@Override
	public void set(V newValue) {
		working.set(newValue);
	}

	@Override
	public V getAndSet(V newValue) {
		return working.getAndSet(newValue);
	}

	@Override
	public boolean compareAndSet(V expected,V newValue) {
		return working.compareAndSet(expected,newValue);
	}

	@Override
	public V getAndUpdate(UnaryOperator<V> updateFunction) {
		return working.getAndUpdate(updateFunction);
	}

	@Override
	public V updateAndGet(UnaryOperator<V> updateFunction) {
		return working.updateAndGet(updateFunction);
	}

	@Override
	public V getAndAccumulate(V x,BinaryOperator<V> accumulatorFunction) {
		return working.getAndAccumulate(x,accumulatorFunction);
	}

	@Override
	public V accumulateAndGet(V x,BinaryOperator<V> accumulatorFunction) {
		return working.accumulateAndGet(x,accumulatorFunction);
	}
}
