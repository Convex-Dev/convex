package convex.lattice;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

import convex.core.data.ACell;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Transactions;

/**
 * Exclusive transactions on a component's live state, made by
 * {@link TransactionalComponent#exclusive()}. Each callback runs once, on a private
 * draft, while holding the write lock of the component's root, and commits before the
 * lock is released. Use it for operations which must not repeat.
 *
 * <p>Like a fork, this is built from the component's bound storage. A fork derives
 * private working storage and rebinds the domain component to it; this derives a
 * read-only view, and rebinds the domain component to a fresh draft for each
 * transaction. Reads see the source's current bound value, before any editing
 * policies. Writes and sync through this component throw
 * {@link convex.lattice.cursor.ReadOnlyCursorException}, and merges, forks,
 * persistence and non-empty paths are refused as the base component refuses them:
 * a transaction is its only way to change state.
 * Commits go to the source's bound storage, which this component holds privately
 * because the read-only view deliberately carries no write access.</p>
 *
 * <p>The lock belongs to the source's root. For a staged component it covers only
 * that component's working copy, so the fork's sync waits for a running transaction;
 * for a live component it covers every writer of the host. A fork's working storage
 * always has such a lock, so {@code fork().exclusive()} is available over any root.
 * A transaction on a staged component commits only to its fork; publishing remains a
 * separate sync. Keep callbacks short: they must not wait for another operation on
 * the same state, sync it, or write through a captured live component.</p>
 *
 * @param <V> Lattice value type
 * @param <C> Domain component type supplied to callbacks
 */
public final class ExclusiveComponent<V extends ACell,C> extends ALatticeComponent<V> {

	/** Bound storage of the source component, where commits go. */
	private final AForkableCursor<V> target;

	/** Binds the source's domain operations to each draft. */
	private final Function<? super AForkableCursor<V>,? extends C> withCursor;

	ExclusiveComponent(AForkableCursor<V> target,Function<? super AForkableCursor<V>,? extends C> withCursor) {
		super(target.readOnly());
		if (!target.supportsExclusiveTransactions()) {
			throw new UnsupportedOperationException("Cursor does not support exclusive transactions");
		}
		this.target=target;
		this.withCursor=Objects.requireNonNull(withCursor);
	}

	/**
	 * Runs domain operations once on a private view under the root's write lock,
	 * commits them, and returns their result.
	 *
	 * @param <R> Result type
	 * @param action Domain operations on a private view, returning their result
	 * @return Result of the callback
	 */
	public <R> R transact(Function<? super C,? extends R> action) {
		Objects.requireNonNull(action);
		return Transactions.transactExclusively(target,draft -> action.apply(withCursor.apply(draft)));
	}

	/**
	 * Runs domain operations once with no result, as {@link #transact}.
	 *
	 * @param action Domain operations on a private view
	 */
	public void execute(Consumer<? super C> action) {
		Objects.requireNonNull(action);
		Transactions.executeExclusively(target,draft -> action.accept(withCursor.apply(draft)));
	}
}
