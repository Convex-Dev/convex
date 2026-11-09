package convex.lattice.cursor;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.util.Utils;

/**
 * Independent working storage which reconciles with a destination on sync.
 * An unchanged fork adopts the destination; an unchanged destination accepts
 * the working value. When both changed, the merge supplied to sync decides.
 *
 * <p>Sync updates the destination atomically while holding the claim on the whole
 * working root. Working writes are ordered around sync; reads never wait. Failed
 * reconciliation or destination preparation leaves the working value and
 * baseline unchanged. Successful publication requires no further merge.</p>
 *
 * <p>The destination has the same value type as the fork. Compose a cursor view,
 * such as {@link SignedCursor}, for representation changes or write policy.
 * Sync stops at that destination and never publishes it further.</p>
 *
 * <p>Merge and destination write functions must not mutate or sync this fork,
 * or wait for another operation on it.</p>
 *
 * @param <V> Working and destination value type
 */
public final class ForkedCursor<V extends ACell> extends AForkableCursor<V> {

	private final ACursor<V> destination;
	private final Root<V> working;
	/** Last reconciled destination value. Moved by sync under the claim; persist swaps in only an equal value. */
	private final AtomicReference<V> baseline;

	private ForkedCursor(ACursor<V> destination) {
		this.destination=Objects.requireNonNull(destination,"Destination must not be null");
		// A fork can prepare a new entry; no destination state is created here.
		V initial=destination.getOrDefault(null);
		this.baseline=new AtomicReference<>(initial);
		this.working=new Root<>(initial);
	}

	/**
	 * Creates an independent working copy of the destination.
	 * @param <V> Value type
	 * @param destination Cursor the copy publishes into
	 * @return Working copy initialised from the destination
	 */
	public static <V extends ACell> ForkedCursor<V> create(ACursor<V> destination) {
		return new ForkedCursor<>(destination);
	}

	/**
	 * Reconciles and publishes through one atomic destination update. Its cursor
	 * handles CAS retries and any signing or stamping. Concurrent working writes
	 * wait until publication and adoption of the result are complete.
	 * @param merge Reconciliation of working and destination values when both changed;
	 *        must be retry-safe and must not mutate either cursor
	 * @return Published value
	 */
	@Override
	public V sync(BinaryOperator<V> merge) {
		Objects.requireNonNull(merge,"Merge function must not be null");
		return reconcile((base,own,other) -> merge.apply(own,other));
	}

	/**
	 * As {@link #sync}, also giving the merge the value both sides last agreed on. On a
	 * retry that is the destination value already merged, so it sees only what is new.
	 * @param merge Reconciliation of base, working and destination values when both
	 *        changed; must be retry-safe and must not mutate either cursor
	 * @return Published value
	 */
	@Override
	public V reconcile(ThreeWayMerge<V> merge) {
		Objects.requireNonNull(merge,"Merge function must not be null");
		try (Claim claim=working.claim(value -> value)) {
			V published=destination.updateAndGet(new Reconciliation(working.get(),merge));
			working.set(published);
			baseline.set(published);
			return published;
		}
	}

	/** Retains reconciled changes as the destination update retries. */
	private final class Reconciliation implements UnaryOperator<V> {
		private final ThreeWayMerge<V> merge;
		private V previous=baseline.get();
		private V candidate;

		Reconciliation(V snapshot,ThreeWayMerge<V> merge) {
			this.merge=merge;
			candidate=snapshot;
		}

		@Override public V apply(V other) {
			if (Utils.equals(candidate,previous) || Utils.equals(candidate,other)) {
				candidate=other;
			} else if (!Utils.equals(other,previous)) {
				candidate=merge.merge(previous,candidate,other);
			}
			previous=other;
			return candidate;
		}
	}

	/**
	 * Persists the working value as any cursor does, then the reconciled baseline,
	 * so the fork keeps no copy the persister has made redundant. This takes no claim,
	 * so it never waits for a transaction. The baseline is replaced only if no sync
	 * has moved it meanwhile; a moved baseline waits for the next persist.
	 */
	@Override
	public V persist(Persister<V> persister) throws IOException {
		V persisted=working.persist(persister);
		V previous=baseline.get();
		if (previous!=null) baseline.compareAndSet(previous,persister.persist(previous));
		return persisted;
	}

	// ===== Working storage =====

	@Override
	protected Claim claim(Function<? super V,? extends ACell> reader) {
		return working.claim(reader);
	}

	/** Reads the working value without waiting; a read during sync sees the value from before it. */
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
