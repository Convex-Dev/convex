package convex.lattice.cursor;

import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;

import convex.core.data.ACell;
import convex.core.util.Utils;

/**
 * Atomic subtree transactions over a cursor: run a callback against a private draft
 * of the cursor's value, then commit by compare-and-set, retrying while the live
 * value changes underneath.
 *
 * <p>This is {@code updateAndGet} whose update function receives a cursor instead of
 * a bare value, so a callback can navigate, wrap boundaries and write through
 * descendants exactly as it would against the live tree. The draft is confined to
 * the callback thread and lifetime. Typed domain transactions build on this in
 * {@code TransactionalComponent}.</p>
 *
 * <p>Ordinary roots retry optimistically without limit. A root supporting
 * exclusive transactions falls back to its write lock after three failed commit
 * attempts; {@link #transactLocked} runs under that lock from the start.</p>
 */
public final class Transactions {

	private Transactions() {}

	/**
	 * Runs an isolated subtree transaction. An equal input reuses the computed
	 * result; a no-op completes without another write after validating its input.
	 * The callback may repeat; external side effects and writes outside the supplied
	 * draft are not permitted. Exceptions discard the private state.
	 *
	 * @param <V> Value type
	 * @param cursor Cursor whose value the transaction updates
	 * @param action Operations on a private draft, valid only during the callback
	 */
	public static <V extends ACell> void transact(AForkableCursor<V> cursor, Consumer<? super AForkableCursor<V>> action) {
		Objects.requireNonNull(cursor);
		Objects.requireNonNull(action);
		Transaction<V> attempt=new Transaction<>(cursor,action);
		Lock lock=cursor.exclusiveLock();
		for (int failures=0;;failures++) {
			if (attempt.commit()) return;
			if (lock!=null && failures==2) break;
		}
		commitExclusively(attempt,lock);
	}

	/**
	 * Runs one isolated callback while excluding all other writes to the shared root.
	 *
	 * @param <V> Value type
	 * @param cursor Cursor whose root supports exclusive transactions
	 * @param action Operations on a private draft, valid only during the callback
	 * @throws UnsupportedOperationException if the cursor's root is an ordinary optimistic root
	 */
	public static <V extends ACell> void transactLocked(AForkableCursor<V> cursor, Consumer<? super AForkableCursor<V>> action) {
		Objects.requireNonNull(cursor);
		Objects.requireNonNull(action);
		Lock lock=cursor.exclusiveLock();
		if (lock==null) throw new UnsupportedOperationException("Cursor does not support exclusive transactions");
		commitExclusively(new Transaction<>(cursor,action),lock);
	}

	private static <V extends ACell> void commitExclusively(Transaction<V> attempt, Lock lock) {
		lock.lock();
		try {
			if (!attempt.commit()) throw new IllegalStateException("Re-entrant write during transaction commit");
		} finally {
			lock.unlock();
		}
	}

	private static final class Transaction<V extends ACell> {
		private final AForkableCursor<V> cursor;
		private final Consumer<? super AForkableCursor<V>> action;
		private boolean computed;
		private V input;
		private V result;

		Transaction(AForkableCursor<V> cursor, Consumer<? super AForkableCursor<V>> action) {
			this.cursor=cursor;
			this.action=action;
		}

		boolean commit() {
			V observed=cursor.get();
			if (!computed || !Utils.equals(observed,input)) {
				input=observed;
				V initial=(input==null)?cursor.shape().zero():input;
				TransactionCursor<V> draft=new TransactionCursor<>(initial,cursor.shape(),cursor.getContext());
				try {
					action.accept(draft);
					V candidate=draft.get();
					// Merely reading an absent position must not materialise its empty value.
					result=Utils.equals(initial,candidate)?input:candidate;
					computed=true;
				} finally {
					draft.close();
				}
			}
			// Validate against a fresh observation, including no-ops. Its reference
			// may differ from the input while denoting exactly the same value.
			observed=cursor.get();
			if (!Utils.equals(observed,input)) return false;
			if (Utils.equals(observed,result)) return true;
			return cursor.compareAndSet(observed,result);
		}
	}
}
