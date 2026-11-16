package convex.lattice.cursor;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.util.Utils;
import convex.lattice.LatticeOps;
import convex.lattice.MergeConflictException;

/**
 * Atomic subtree transactions over a cursor: run a callback against a private draft
 * of the cursor's value, then commit into that cursor's storage. A commit into a
 * fork updates its working state; syncing the fork to its destination is separate.
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
 * <p>Execution produces a transaction value and a return value. Commit applies any
 * required reconciliation and authoring, then atomically installs the resulting
 * state. {@code transact} returns the callback's result from the execution whose
 * transaction value was used for the successful commit; {@code execute} returns
 * nothing. These additional transformations do not change the execution's return
 * value. The transaction value need not be separately visible in storage before
 * these transformations. The result records what the execution produced, without
 * promising that its changes survive reconciliation or later updates.</p>
 *
 * <p>Optimistic execution is the fast path for transactions that do not collide. A
 * change elsewhere under the root never reruns a callback: its finished result is
 * applied to the new root. A commit is a three-way merge of the value the run started
 * from, the run's value and the value as held, which may be persisted since, as
 * {@code base}, {@code own} and {@code other}. Given a {@link ThreeWayMerge}, the run
 * commits with a concurrent change wherever that merge reconciles them. Without one the
 * merge is strict: the run's changes are carried onto the held value if it is
 * unchanged, and a run that changed nothing commits whatever changed meanwhile, as of
 * the value it read. When the merge throws {@link MergeConflictException}, the
 * transaction takes the {@link Claim} beneath its cursor (see {@link ACursor#claim})
 * and finishes under it. Claims on the same boundary run one at a time, and a callback
 * runs at most twice. {@link #transactExclusively} starts with the claim, for work too
 * expensive to repeat or with side effects: its callback runs once.</p>
 *
 * <p>Under a {@link BoundaryCursor}, a write that does not pass through the boundary
 * can still change the value while it is claimed. The transaction then commits by the
 * same merge, and fails if that merge refuses the change. Without a merge, it fails
 * rather than overwrite the change.</p>
 */
public final class Transactions {

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
	 * committing its run by a merge with any concurrent change, including a change under
	 * the claim that did not pass through it, and returns the committed run's result.
	 *
	 * @param <V> Value type
	 * @param <R> Result type
	 * @param cursor Cursor whose value the transaction updates
	 * @param action Operations on a private draft, completed before the callback returns
	 * @param merge Reconciliation of the value the run started from, its value and the changed value, as base, own and other, throwing {@link MergeConflictException} where they conflict; or null for the strict merge
	 * @return Result of the run that committed, computed before any merge at commit
	 * @throws MissingValueException If the cursor's location does not exist, before the callback runs
	 */
	public static <V extends ACell,R> R transact(AForkableCursor<V> cursor, Function<? super AForkableCursor<V>,? extends R> action, ThreeWayMerge<V> merge) {
		Objects.requireNonNull(cursor);
		Objects.requireNonNull(action);
		Transaction<V,R> attempt=new Transaction<>(cursor,action,merge);
		if (attempt.commit()) return attempt.output;
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
	 * @param merge Reconciliation of the value the run started from, its value and the changed value, as base, own and other, throwing {@link MergeConflictException} where they conflict; or null for the strict merge
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
	 * @param merge Reconciliation of the value the run started from, its value and the changed value, as base, own and other; or null to fail instead
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
	 * @param merge Reconciliation of the value the run started from, its value and the changed value, as base, own and other; or null to fail instead
	 */
	public static <V extends ACell> void executeExclusively(AForkableCursor<V> cursor, Consumer<? super AForkableCursor<V>> action, ThreeWayMerge<V> merge) {
		Objects.requireNonNull(action);
		transactExclusively(cursor,draft -> {
			action.accept(draft);
			return null;
		},merge);
	}

	/**
	 * Commits a run only if nothing else changed its value, because its reads hold only
	 * then. Where its value is held in other cells, such as persisted ones, the run's
	 * changes are carried onto them.
	 */
	private static <V extends ACell> V strict(V base,V own,V other) {
		if (Utils.equals(other,base)) return LatticeOps.mergeChanges(base,own,other);
		throw new MergeConflictException();
	}

	/** As {@link #strict}, for a change under the claim that there is no merge to reconcile. */
	private static <V extends ACell> V refuse(V base,V own,V other) {
		if (Utils.equals(other,base)) return LatticeOps.mergeChanges(base,own,other);
		throw new IllegalStateException("Value changed under a transaction's claim, by this thread or by a write that did not pass through the claimed boundary");
	}

	/** One transaction, and the update function that commits it. */
	private static final class Transaction<V extends ACell,R> implements UnaryOperator<V> {
		private final AForkableCursor<V> cursor;
		private final Function<? super AForkableCursor<V>,? extends R> action;
		/** Reconciles a concurrent change with the computed run, or null for the strict merge. */
		private final ThreeWayMerge<V> merge;
		/** Merge of the commit in progress. */
		private ThreeWayMerge<V> merging;
		private boolean computed;
		/** Value the computed run started from, then the last value its commit merged with. */
		private V base;
		/** Value the computed run leaves in its draft, then the last value its commit made. */
		private V own;
		/** Result of the computed run, which is the committed run once commit succeeds. */
		private R output;

		Transaction(AForkableCursor<V> cursor, Function<? super AForkableCursor<V>,? extends R> action, ThreeWayMerge<V> merge) {
			this.cursor=cursor;
			this.action=action;
			this.merge=merge;
		}

		private void run(V value) {
			base=value;
			var draft=new Root<>(base);
			output=action.apply(draft);
			V result=draft.get();
			// Preserve the original immutable value for a no-op.
			own=Utils.equals(base,result)?base:result;
			computed=true;
		}

		@Override public V apply(V other) {
			own=Reconciler.reconcile(base,own,other,merging);
			base=other;
			return own;
		}

		/** The one optimistic attempt. False leaves the caller to take the claim. */
		boolean commit() {
			run(cursor.get());
			merging=(merge!=null)?merge:Transactions::strict;
			try {
				// A run that changed nothing still commits as a write, so a cursor that cannot be written refuses it
				cursor.updateAndGet(this);
				return true;
			} catch (MergeConflictException e) {
				return false;
			}
		}

		/**
		 * Finishes under the claim, where the value can change only through this thread
		 * or through a write that bypasses the claimed boundary, which is merged or refused.
		 */
		R commitClaimed() {
			try (Claim claim=cursor.claim()) {
				V other=cursor.get();
				if (!computed || !Utils.equals(other,base)) run(other);
				merging=(merge!=null)?merge:Transactions::refuse;
				cursor.updateAndGet(this);
				return output;
			}
		}
	}
}
