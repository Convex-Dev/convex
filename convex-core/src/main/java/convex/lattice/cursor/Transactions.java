package convex.lattice.cursor;

import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;
import java.util.function.Function;

import convex.core.data.ACell;
import convex.core.util.Utils;

/**
 * Atomic subtree transactions over a cursor: run a callback against a private draft
 * of the cursor's value, then commit by compare-and-set, retrying while the live
 * value changes underneath.
 *
 * <p>This is {@code updateAndGet} whose update function receives a cursor instead of
 * a bare value, so a callback can navigate, wrap boundaries and write through
 * descendants exactly as it would against the live tree. The draft uses independent,
 * thread-safe root storage. Complete all work contributing to the transaction before
 * returning from the callback; later edits only change the retained draft.
 * Typed domain transactions build on this in
 * {@code TransactionalComponent}.</p>
 *
 * <p>{@code transact} returns the callback's result from the run that committed, so
 * it describes exactly the committed change; {@code execute} returns nothing. A
 * result is a local fact about this commit, not about what replicas hold after
 * merging.</p>
 *
 * <p>Ordinary roots retry optimistically without limit. A root supporting
 * exclusive transactions falls back to its write lock after three failed commit
 * attempts; {@link #transactExclusively} runs once under that lock from the start.</p>
 */
public final class Transactions {

	private Transactions() {}

	/**
	 * Runs an isolated subtree transaction and returns the committed run's result. An
	 * equal input reuses the computed run; a no-op completes without another write
	 * after validating its input. The callback may repeat; external side effects and
	 * writes outside the supplied draft are not permitted. Exceptions discard the
	 * private state.
	 *
	 * @param <V> Value type
	 * @param <R> Result type
	 * @param cursor Cursor whose value the transaction updates
	 * @param action Operations on a private draft, completed before the callback returns
	 * @return Result of the run that committed
	 */
	public static <V extends ACell,R> R transact(AForkableCursor<V> cursor, Function<? super AForkableCursor<V>,? extends R> action) {
		Objects.requireNonNull(cursor);
		Objects.requireNonNull(action);
		Transaction<V,R> attempt=new Transaction<>(cursor,action);
		Lock lock=cursor.exclusiveLock();
		for (int failures=0;;failures++) {
			if (attempt.commit()) return attempt.output;
			if (lock!=null && failures==2) break;
		}
		return commitExclusively(attempt,lock);
	}

	/**
	 * Runs an isolated subtree transaction with no result, as {@link #transact}.
	 *
	 * @param <V> Value type
	 * @param cursor Cursor whose value the transaction updates
	 * @param action Operations on a private draft, completed before the callback returns
	 */
	public static <V extends ACell> void execute(AForkableCursor<V> cursor, Consumer<? super AForkableCursor<V>> action) {
		Objects.requireNonNull(action);
		transact(cursor,draft -> {
			action.accept(draft);
			return null;
		});
	}

	/**
	 * Runs one isolated callback while excluding all other writes to the shared root,
	 * and returns its result.
	 *
	 * @param <V> Value type
	 * @param <R> Result type
	 * @param cursor Cursor whose root supports exclusive transactions
	 * @param action Operations on a private draft, completed before the callback returns
	 * @return Result of the callback, which ran once
	 * @throws UnsupportedOperationException if the cursor's root is an ordinary optimistic root
	 */
	public static <V extends ACell,R> R transactExclusively(AForkableCursor<V> cursor, Function<? super AForkableCursor<V>,? extends R> action) {
		Objects.requireNonNull(cursor);
		Objects.requireNonNull(action);
		Lock lock=cursor.exclusiveLock();
		if (lock==null) throw new UnsupportedOperationException("Cursor does not support exclusive transactions");
		return commitExclusively(new Transaction<>(cursor,action),lock);
	}

	/**
	 * Runs one isolated callback with no result, as {@link #transactExclusively}.
	 *
	 * @param <V> Value type
	 * @param cursor Cursor whose root supports exclusive transactions
	 * @param action Operations on a private draft, completed before the callback returns
	 * @throws UnsupportedOperationException if the cursor's root is an ordinary optimistic root
	 */
	public static <V extends ACell> void executeExclusively(AForkableCursor<V> cursor, Consumer<? super AForkableCursor<V>> action) {
		Objects.requireNonNull(action);
		transactExclusively(cursor,draft -> {
			action.accept(draft);
			return null;
		});
	}

	private static <V extends ACell,R> R commitExclusively(Transaction<V,R> attempt, Lock lock) {
		lock.lock();
		try {
			if (!attempt.commit()) throw new IllegalStateException("Re-entrant write during transaction commit");
			return attempt.output;
		} finally {
			lock.unlock();
		}
	}

	private static final class Transaction<V extends ACell,R> {
		private final AForkableCursor<V> cursor;
		private final Function<? super AForkableCursor<V>,? extends R> action;
		private boolean computed;
		private V input;
		/** Value the computed run leaves in its draft. */
		private V candidate;
		/** Result of the computed run, which is the committed run once commit succeeds. */
		private R output;

		Transaction(AForkableCursor<V> cursor, Function<? super AForkableCursor<V>,? extends R> action) {
			this.cursor=cursor;
			this.action=action;
		}

		boolean commit() {
			// A transaction can create a missing final entry, as an atomic update can.
			V observed=cursor.getOrDefault(null);
			if (!computed || !Utils.equals(observed,input)) {
				input=observed;
				var draft=new TransactionalRootCursor<>(input);
				output=action.apply(draft);
				V value=draft.get();
				// Preserve the original immutable value for a no-op.
				candidate=Utils.equals(input,value)?input:value;
				computed=true;
			}
			// Validate against a fresh observation, including no-ops. Its reference
			// may differ from the input while denoting exactly the same value.
			observed=cursor.getOrDefault(null);
			if (!Utils.equals(observed,input)) return false;
			if (Utils.equals(observed,candidate)) return true;
			return cursor.compareAndSet(observed,candidate);
		}
	}
}
