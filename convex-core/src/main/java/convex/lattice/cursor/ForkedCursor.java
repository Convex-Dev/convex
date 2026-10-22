package convex.lattice.cursor;

import java.util.Objects;
import java.util.concurrent.locks.Lock;
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
 * Working writes are ordered before or after sync; reconciliation and preparation
 * finish before publication commits. A failed merge or preparation changes neither
 * the destination nor the working value or baseline.
 * Publication stops at the destination and never publishes that cursor further.</p>
 *
 * <p>The fork knows no lattice, context or signing policy: the functions it is
 * given carry whatever policy their owner closed over. A type-changing destination,
 * such as a signed envelope, supplies {@code read} and {@code prepare} functions.
 * Preparation runs once per distinct candidate, outside the atomic compare-and-set,
 * so an unrelated concurrent write reuses a prepared value such as a signature.</p>
 *
 * <p>Working storage uses {@link TransactionalRootCursor}. Sync holds its write
 * lock through the destination CAS and installation of the reconciled working value.
 * Reads of this fork also take that lock, so they cannot observe an incomplete sync.
 * The destination remains independently writable and CAS conflicts retry normally.</p>
 *
 * <p>Read, prepare and merge functions must not mutate or sync this fork, or wait
 * for another operation on it.</p>
 *
 * @param <V> Working value type
 */
public final class ForkedCursor<V extends ACell> extends AForkableCursor<V> {

	private final Target<V,?> target;
	private final BinaryOperator<V> merge;
	private final TransactionalRootCursor<V> working;

	private <P extends ACell> ForkedCursor(ACursor<P> destination,Function<P,V> read,
			Function<V,P> prepare,BinaryOperator<V> merge) {
		Target<V,P> t=new Target<>(destination,read,prepare);
		this.target=t;
		this.merge=merge;
		this.working=new TransactionalRootCursor<>(t.baseline);
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
	 * Atomically reconciles and publishes the working value into the destination.
	 * All fallible computation precedes the destination CAS. Working writes wait
	 * until sync completes; there is no reconciliation after publication.
	 *
	 * @return Published value
	 */
	public V sync() {
		Lock lock=working.exclusiveLock();
		lock.lock();
		try {
			V published=target.publish(working.get(),merge);
			working.set(published);
			return published;
		} finally {
			lock.unlock();
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
		/** Destination value the working copy was last reconciled against; under the working root lock. */
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
		Lock lock=working.exclusiveLock();
		lock.lock();
		try { return working.get(); } finally { lock.unlock(); }
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
