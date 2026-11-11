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
 * takes the {@link Claim} beneath its cursor (see {@link ACursor#claim}) and finishes
 * under it, so transactions that collide run one at a time and a callback runs at
 * most three times. {@link #transactExclusively} starts with the claim, for work too
 * expensive to repeat or with side effects: its callback runs once.</p>
 *
 * <p>Under a {@link BoundaryCursor}, a write that does not pass through the boundary
 * can still change the value while it is claimed. Given a {@link ThreeWayMerge}, the
 * transaction then commits the merge of its run with that change, its run's value as
 * {@code own} and the changed value as {@code other}, as a fork's reconciliation
 * does. Without one, it fails rather than overwrite the change.</p>
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
	 * private state. A change under the claim that did not pass through it makes the
	 * transaction fail.
	 *
	 * @param <V> Value type
	 * @param <R> Result type
	 * @param cursor Cursor whose value the transaction updates
	 * @param action Operations on a private draft, completed before the callback returns
	 * @return Result of the run that committed
	 * @throws MissingValueException If the cursor's location does not exist, before the callback runs
	 */
	public static <V extends ACell,R> R transact(AForkableCursor<V> cursor, Function<? super AForkableCursor<V>,? extends R> action) {
		return transact(cursor,action,null);
	}

	/**
	 * Runs an isolated subtree transaction as {@link #transact(AForkableCursor,Function)},
	 * merging a change under the claim that did not pass through it with the committed
	 * run, whose result is returned.
	 *
	 * @param <V> Value type
	 * @param <R> Result type
	 * @param cursor Cursor whose value the transaction updates
	 * @param action Operations on a private draft, completed before the callback returns
	 * @param merge Reconciliation of the run's input, its value and the changed value, or null to fail instead
	 * @return Result of the run that committed, computed before any merge at commit
	 * @throws MissingValueException If the cursor's location does not exist, before the callback runs
	 */
	public static <V extends ACell,R> R transact(AForkableCursor<V> cursor, Function<? super AForkableCursor<V>,? extends R> action, ThreeWayMerge<V> merge) {
		Objects.requireNonNull(cursor);
		Objects.requireNonNull(action);
		Transaction<V,R> attempt=new Transaction<>(cursor,action,merge);
		while (attempt.conflicts<CONFLICTS_BEFORE_CLAIM) {
			if (attempt.commit()) return attempt.output;
		}
		return attempt.commitClaimed();
	}

	/**
	 * Runs an isolated subtree transaction with no result, as {@link #transact(AForkableCursor,Function)}.
	 *
	 * @param <V> Value type
	 * @param cursor Cursor whose value the transaction updates
	 * @param action Operations on a private draft, completed before the callback returns
	 */
	public static <V extends ACell> void execute(AForkableCursor<V> cursor, Consumer<? super AForkableCursor<V>> action) {
		execute(cursor,action,null);
	}

	/**
	 * Runs an isolated subtree transaction with no result, as {@link #transact(AForkableCursor,Function,ThreeWayMerge)}.
	 *
	 * @param <V> Value type
	 * @param cursor Cursor whose value the transaction updates
	 * @param action Operations on a private draft, completed before the callback returns
	 * @param merge Reconciliation of the run's input, its value and the changed value, or null to fail instead
	 */
	public static <V extends ACell> void execute(AForkableCursor<V> cursor, Consumer<? super AForkableCursor<V>> action, ThreeWayMerge<V> merge) {
		Objects.requireNonNull(action);
		transact(cursor,draft -> {
			action.accept(draft);
			return null;
		},merge);
	}

	/**
	 * Runs one isolated callback, once, under the claim beneath this cursor (see
	 * {@link ACursor#claim}), and returns its result. Writes through that boundary
	 * wait until it commits, and so do other claims on it. A change under the claim
	 * that did not pass through it makes the transaction fail.
	 *
	 * @param <V> Value type
	 * @param <R> Result type
	 * @param cursor Cursor whose value the transaction updates
	 * @param action Operations on a private draft, completed before the callback returns
	 * @return Result of the callback, which ran once
	 * @throws MissingValueException If the cursor's location does not exist, before the callback runs
	 */
	public static <V extends ACell,R> R transactExclusively(AForkableCursor<V> cursor, Function<? super AForkableCursor<V>,? extends R> action) {
		return transactExclusively(cursor,action,null);
	}

	/**
	 * Runs one isolated callback, once, as {@link #transactExclusively(AForkableCursor,Function)},
	 * merging a change under the claim that did not pass through it with the callback's run.
	 *
	 * @param <V> Value type
	 * @param <R> Result type
	 * @param cursor Cursor whose value the transaction updates
	 * @param action Operations on a private draft, completed before the callback returns
	 * @param merge Reconciliation of the run's input, its value and the changed value, or null to fail instead
	 * @return Result of the callback, which ran once, computed before any merge at commit
	 * @throws MissingValueException If the cursor's location does not exist, before the callback runs
	 */
	public static <V extends ACell,R> R transactExclusively(AForkableCursor<V> cursor, Function<? super AForkableCursor<V>,? extends R> action, ThreeWayMerge<V> merge) {
		Objects.requireNonNull(cursor);
		Objects.requireNonNull(action);
		return new Transaction<>(cursor,action,merge).commitClaimed();
	}

	/**
	 * Runs one isolated callback with no result, as {@link #transactExclusively(AForkableCursor,Function)}.
	 *
	 * @param <V> Value type
	 * @param cursor Cursor whose value the transaction updates
	 * @param action Operations on a private draft, completed before the callback returns
	 */
	public static <V extends ACell> void executeExclusively(AForkableCursor<V> cursor, Consumer<? super AForkableCursor<V>> action) {
		executeExclusively(cursor,action,null);
	}

	/**
	 * Runs one isolated callback with no result, as {@link #transactExclusively(AForkableCursor,Function,ThreeWayMerge)}.
	 *
	 * @param <V> Value type
	 * @param cursor Cursor whose value the transaction updates
	 * @param action Operations on a private draft, completed before the callback returns
	 * @param merge Reconciliation of the run's input, its value and the changed value, or null to fail instead
	 */
	public static <V extends ACell> void executeExclusively(AForkableCursor<V> cursor, Consumer<? super AForkableCursor<V>> action, ThreeWayMerge<V> merge) {
		Objects.requireNonNull(action);
		transactExclusively(cursor,draft -> {
			action.accept(draft);
			return null;
		},merge);
	}

	private static final class Transaction<V extends ACell,R> {
		private final AForkableCursor<V> cursor;
		private final Function<? super AForkableCursor<V>,? extends R> action;
		/** Reconciles a change under the claim with the computed run, or null to refuse it. */
		private final ThreeWayMerge<V> merge;
		private boolean computed;
		private V input;
		/** Value the computed run leaves in its draft. */
		private V candidate;
		/** Result of the computed run, which is the committed run once commit succeeds. */
		private R output;
		/** Times a computed run was invalidated by a change to the transaction's own value. */
		int conflicts;

		Transaction(AForkableCursor<V> cursor, Function<? super AForkableCursor<V>,? extends R> action, ThreeWayMerge<V> merge) {
			this.cursor=cursor;
			this.action=action;
			this.merge=merge;
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

		/**
		 * Finishes under the claim, where the value can change only through this thread
		 * or through a write that bypasses the claimed boundary, which is merged or refused.
		 */
		R commitClaimed() {
			try (Claim claim=cursor.claim()) {
				V observed=cursor.get();
				if (!computed || !Utils.equals(observed,input)) run(observed);
				if (merge!=null) {
					cursor.updateAndGet(new Reconciliation<>(input,candidate,merge));
					return output;
				}
				for (;;) {
					observed=cursor.get();
					if (!Utils.equals(observed,input)) {
						throw new IllegalStateException("Value changed under a transaction's claim, by this thread or by a write that did not pass through the claimed boundary");
					}
					if (cursor.compareAndSet(observed,candidate)) return output;
				}
			}
		}
	}
}
