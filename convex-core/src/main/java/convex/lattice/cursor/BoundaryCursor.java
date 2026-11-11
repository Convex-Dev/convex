package convex.lattice.cursor;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BinaryOperator;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;

/**
 * A transactional boundary over another cursor. Reads and writes go straight
 * through to that cursor, so every write is visible there at once, as through a
 * path. Claims stop here: a transaction on this cursor, or on a path or view over
 * it, claims this boundary rather than the root holding the value, so exclusive
 * transactions under separate boundaries run at the same time.
 *
 * <p>While this boundary is claimed, writes through it from other threads wait
 * until the claim is released; the claim's owner writes freely. A claim covers
 * everything under this boundary, whatever part a transaction reads. Writes that
 * reach the same storage without passing through this boundary do not wait; a
 * claimed transaction whose value they change merges them at commit or fails, as
 * {@link Transactions} describes. Writes through this boundary pass through whatever cursor it
 * wraps, so a claim there, such as on an enclosing boundary or root, still stops
 * them.</p>
 *
 * <p>Persisting is not a write: it goes straight through and never waits.</p>
 *
 * @param <V> Value type
 */
public final class BoundaryCursor<V extends ACell> extends AForkableCursor<V> {

	private final AForkableCursor<V> source;

	/** Writes through this boundary hold the read side; a claim holds the write side. */
	private final ReentrantReadWriteLock lock=new ReentrantReadWriteLock();

	private BoundaryCursor(AForkableCursor<V> source) {
		this.source=Objects.requireNonNull(source,"Cursor must not be null");
	}

	/**
	 * Returns a transactional boundary over a cursor.
	 *
	 * @param <V> Value type
	 * @param cursor Cursor to wrap
	 * @return A new boundary over the cursor, or the cursor itself if it is a boundary
	 */
	public static <V extends ACell> BoundaryCursor<V> over(AForkableCursor<V> cursor) {
		if (cursor instanceof BoundaryCursor<V> boundary) return boundary;
		return new BoundaryCursor<>(cursor);
	}

	/**
	 * Takes this boundary's claim, waiting for any earlier claim and for writes
	 * through it to finish.
	 *
	 * @throws IllegalStateException if the calling thread already holds this claim,
	 *         or is writing through this boundary
	 */
	@Override
	protected Claim claim() {
		if (lock.isWriteLockedByCurrentThread()) throw new IllegalStateException("This thread already holds this boundary's claim");
		if (lock.getReadHoldCount()>0) throw new IllegalStateException("A write through this boundary cannot claim it");
		ReentrantReadWriteLock.WriteLock claim=lock.writeLock();
		claim.lock();
		return claim::unlock;
	}

	/** Runs one write through this boundary, after any claim held by another thread. */
	private <T> T write(Supplier<T> write) {
		ReentrantReadWriteLock.ReadLock permit=lock.readLock();
		permit.lock();
		try {
			return write.get();
		} finally {
			permit.unlock();
		}
	}

	@Override public V get() { return source.get(); }
	@Override protected Object readValue() { return source.readValue(); }

	@Override public V getAndSet(V newValue) { return write(() -> source.getAndSet(newValue)); }
	@Override public boolean compareAndSet(V expected,V newValue) { return write(() -> source.compareAndSet(expected,newValue)); }
	@Override public void set(V newValue) { write(() -> { source.set(newValue); return null; }); }
	@Override public V getAndUpdate(UnaryOperator<V> updateFunction) { return write(() -> source.getAndUpdate(updateFunction)); }
	@Override public V updateAndGet(UnaryOperator<V> updateFunction) { return write(() -> source.updateAndGet(updateFunction)); }
	@Override public V getAndAccumulate(V x,BinaryOperator<V> accumulatorFunction) { return write(() -> source.getAndAccumulate(x,accumulatorFunction)); }
	@Override public V accumulateAndGet(V x,BinaryOperator<V> accumulatorFunction) { return write(() -> source.accumulateAndGet(x,accumulatorFunction)); }
	@Override public V sync(BinaryOperator<V> merge) { return write(() -> source.sync(merge)); }
	@Override public V reconcile(ThreeWayMerge<V> merge) { return write(() -> source.reconcile(merge)); }

	@Override public V persist(Persister<V> persister) throws IOException { return source.persist(persister); }
}
