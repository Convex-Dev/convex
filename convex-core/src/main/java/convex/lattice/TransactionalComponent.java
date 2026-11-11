package convex.lattice;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

import convex.core.data.ACell;
import convex.core.util.ThreadUtils;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Transactions;

/**
 * Typed, atomic operations on a component's lattice subtree.
 *
 * <p>Callbacks receive an isolated component view. They may run more than once,
 * must use that view for all lattice changes, and must not perform external side
 * effects. Descendants share the private working state. Exceptions discard the
 * attempt. Successful completion means committed locally, not published or durable.
 * Complete all draft work before returning from the callback. Retained views and
 * their forks remain independent storage; later edits do not change live state.</p>
 *
 * <p>{@link #transact} returns the callback's result from the run that committed;
 * {@link #execute} returns nothing. {@link #exclusive()} gives the form whose
 * callbacks run once under the root's claim on this component's value.</p>
 *
 * <p>An {@link ALatticeComponent} opts in by declaring this interface and refining
 * {@link ALatticeComponent#rebind} to return its own type; its bound storage comes
 * from the base class, and any root will do. Application subclasses can opt in without changing their base class or introducing a component
 * registry. The engine is
 * {@link Transactions}; this interface adds the typed component view. Rebinding
 * rebuilds the component's editing views over the draft, so a stamp the component
 * builds over its storage applies within the draft. Authoring within bound storage,
 * such as a signing view or a stamp bound there, applies at each commit attempt. Commit uses
 * bound storage so editing views are not applied twice.</p>
 *
 * <p>Transactions rerun domain operations on conflict; they do not invoke incoming
 * merge or fork reconciliation. No lattice definition or publication merge is
 * required. Choose a component containing every value the operation reads or
 * writes, and obtain children from the callback's view. A transaction on an
 * existing fork commits only to that fork; publishing it remains a separate sync.</p>
 *
 * <p>A transaction on a child is isolated to that child's storage. A transaction on
 * a parent spans its children: those made on the callback's view write into the
 * draft, and a parent that manages children in forks of their own has them synced
 * into the draft, through {@link ALatticeComponent#syncChildren()}, after the
 * callback returns and before the draft commits.</p>
 *
 * @param <V> Lattice value type
 * @param <C> Domain component type supplied to callbacks
 */
public interface TransactionalComponent<V extends ACell,C> {

	/** Returns bound working storage, before editing policies reinstalled by rebind. */
	AForkableCursor<V> storage();

	/**
	 * Makes this component's counterpart over one attempt's private draft. See
	 * {@link ALatticeComponent#rebind} for what it does with state outside the cursor.
	 * Do not fork the supplied cursor: the transaction engine reads its completed
	 * value directly.
	 */
	C rebind(AForkableCursor<V> cursor);

	/**
	 * Applies domain operations atomically and returns the result of the run that
	 * committed. Runs optimistically; if this component's value keeps changing, the
	 * transaction takes the root's claim and finishes under it, so the callback runs at
	 * most three times. Changes elsewhere under the root never rerun it. Equality,
	 * rather than Java object identity, determines whether an input has changed or an
	 * operation leaves that input unchanged.
	 *
	 * @param <R> Result type
	 * @param action Domain operations on a private view, returning their result
	 * @return Result of the run that committed
	 * @throws convex.lattice.cursor.MissingValueException If this component's storage does not exist, before the callback runs
	 */
	default <R> R transact(Function<? super C,? extends R> action) {
		Objects.requireNonNull(action);
		return Transactions.transact(storage(),draft -> {
			C view=rebind(draft);
			R result=action.apply(view);
			ALatticeComponent.completeDraft(view);
			return result;
		});
	}

	/**
	 * Applies domain operations atomically with no result, as {@link #transact}.
	 *
	 * @param action Domain operations on a private view
	 */
	default void execute(Consumer<? super C> action) {
		Objects.requireNonNull(action);
		Transactions.execute(storage(),draft -> {
			C view=rebind(draft);
			action.accept(view);
			ALatticeComponent.completeDraft(view);
		});
	}

	/**
	 * Returns a read-only component over this one's bound storage whose transactions
	 * each run once under the root's claim on this component's value.
	 *
	 * @return Exclusive transactions on this component's state
	 */
	default ExclusiveComponent<V,C> exclusive() {
		return new ExclusiveComponent<>(storage(),this::rebind);
	}

	/**
	 * Schedules {@link #transact} on the shared virtual executor. The future completes
	 * with the result after local commit, or exceptionally on failure. Cancellation does
	 * not undo a commit or guarantee that a running callback stops. No component owns a thread.
	 */
	default <R> CompletableFuture<R> transactAsync(Function<? super C,? extends R> action) {
		return transactAsync(action,ThreadUtils.getVirtualExecutor());
	}

	/** Uses a caller-supplied executor, including a direct executor if desired. */
	default <R> CompletableFuture<R> transactAsync(Function<? super C,? extends R> action, Executor executor) {
		Objects.requireNonNull(action);
		return CompletableFuture.supplyAsync(() -> transact(action),executor);
	}

	/** Schedules {@link #execute} on the shared virtual executor, as {@link #transactAsync(Function)}. */
	default CompletableFuture<Void> executeAsync(Consumer<? super C> action) {
		return executeAsync(action,ThreadUtils.getVirtualExecutor());
	}

	/** Uses a caller-supplied executor, including a direct executor if desired. */
	default CompletableFuture<Void> executeAsync(Consumer<? super C> action, Executor executor) {
		Objects.requireNonNull(action);
		return CompletableFuture.runAsync(() -> execute(action),executor);
	}
}
