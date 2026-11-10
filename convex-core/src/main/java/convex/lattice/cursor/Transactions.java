package convex.lattice.cursor;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

import convex.core.data.ACell;
import convex.core.util.Utils;

/**
 * Atomic subtree transactions over a cursor: run a callback against a private draft
 * of the cursor's value, then commit by compare-and-set.
 *
 * <p>The callback receives a cursor instead of a bare value, so it can navigate, wrap
 * boundaries and write through descendants exactly as it would against the live
 * tree. A transaction starts with a required read of the cursor, so its location
 * must exist. The draft uses independent,
 * thread-safe root storage. Complete all work contributing to the transaction before
 * returning from the callback; later edits only change the retained draft.
 * Typed domain transactions build on this in
 * {@code TransactionalComponent}.</p>
 *
 * <p>{@code transact} returns the callback's result from the run that committed;
 * {@code execute} returns nothing.</p>
 *
 * <p>Optimistic execution is the fast path for transactions that do not collide. A
 * change elsewhere under the root never reruns a callback: its finished result is
 * applied to the new root. When the transaction's own value has changed twice, it
 * takes the root's one {@link Claim} and finishes under it, so transactions that
 * collide run one at a time and a callback runs at most three times.
 * {@link #transactExclusively} starts with the claim, for work too expensive to
 * repeat or with side effects: its callback runs once.</p>
 */
public final class Transactions {

	/** Changes to a transaction's own value it accepts before taking the claim. */
	private static final int CONFLICTS_BEFORE_CLAIM=2;

	private Transactions() {}

	/**
	 * Runs an isolated subtree transaction and returns the committed run's result. An
	 * equal input reuses the computed run; a no-op commits as a write that changes
	 * nothing. The callback may repeat; external side effects and
	 * writes outside the supplied draft are not permitted. Exceptions discard the
	 * private state.
	 *
	 * @param <V> Value type
	 * @param <R> Result type
	 * @param cursor Cursor whose value the transaction updates
	 * @param action Operations on a private draft, completed before the callback returns
	 * @return Result of the run that committed
	 * @throws MissingValueException If the cursor's location does not exist, before the callback runs
	 */
	public static <V extends ACell,R> R transact(AForkableCursor<V> cursor, Function<? super AForkableCursor<V>,? extends R> action) {
		Objects.requireNonNull(cursor);
		Objects.requireNonNull(action);
		Transaction<V,R> attempt=new Transaction<>(cursor,action);
		while (attempt.conflicts<CONFLICTS_BEFORE_CLAIM) {
			if (attempt.commit()) return attempt.output;
		}
		return attempt.commitClaimed();
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
	 * Runs one isolated callback, once, under the root's claim on this cursor's value,
	 * and returns its result. Writes that would change this value wait until it
	 * commits, and so do other claims on the root; writes elsewhere carry on.
	 *
	 * @param <V> Value type
	 * @param <R> Result type
	 * @param cursor Cursor whose value the transaction updates
	 * @param action Operations on a private draft, completed before the callback returns
	 * @return Result of the callback, which ran once
	 * @throws MissingValueException If the cursor's location does not exist, before the callback runs
	 */
	public static <V extends ACell,R> R transactExclusively(AForkableCursor<V> cursor, Function<? super AForkableCursor<V>,? extends R> action) {
		Objects.requireNonNull(cursor);
		Objects.requireNonNull(action);
		return new Transaction<>(cursor,action).commitClaimed();
	}

	/**
	 * Runs one isolated callback with no result, as {@link #transactExclusively}.
	 *
	 * @param <V> Value type
	 * @param cursor Cursor whose value the transaction updates
	 * @param action Operations on a private draft, completed before the callback returns
	 */
	public static <V extends ACell> void executeExclusively(AForkableCursor<V> cursor, Consumer<? super AForkableCursor<V>> action) {
		Objects.requireNonNull(action);
		transactExclusively(cursor,draft -> {
			action.accept(draft);
			return null;
		});
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
		/** Times a computed run was invalidated by a change to the transaction's own value. */
		int conflicts;

		Transaction(AForkableCursor<V> cursor, Function<? super AForkableCursor<V>,? extends R> action) {
			this.cursor=cursor;
			this.action=action;
		}

		private void run(V value) {
			input=value;
			var draft=new Root<>(input);
			output=action.apply(draft);
			V result=draft.get();
			// Preserve the original immutable value for a no-op.
			candidate=Utils.equals(input,result)?input:result;
			computed=true;
		}

		/** One optimistic attempt. False leaves the caller to try again or take the claim. */
		boolean commit() {
			V observed=cursor.get();
			if (!computed || !Utils.equals(observed,input)) {
				if (computed && ++conflicts>=CONFLICTS_BEFORE_CLAIM) return false;
				run(observed);
			}
			// Validate against a fresh observation, including no-ops. Its reference
			// may differ from the input while denoting exactly the same value.
			observed=cursor.get();
			if (!Utils.equals(observed,input)) return false;
			// A run that changed nothing still commits as a write, so a cursor that cannot be written refuses it
			return cursor.compareAndSet(observed,candidate);
		}

		/** Finishes under the claim, where the value can change only through this thread. */
		R commitClaimed() {
			try (Claim claim=cursor.claim(value -> value)) {
				V observed=cursor.get();
				if (!computed || !Utils.equals(observed,input)) run(observed);
				for (;;) {
					observed=cursor.get();
					if (!Utils.equals(observed,input)) throw new IllegalStateException("Re-entrant write during transaction commit");
					if (cursor.compareAndSet(observed,candidate)) return output;
				}
			}
		}
	}
}
