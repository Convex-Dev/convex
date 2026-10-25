package convex.lattice;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

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
 * <p>Implementations need only provide their bound storage and a factory which binds the
 * same domain operations and configuration to a supplied cursor. This interface
 * can also be implemented by application subclasses without changing their base
 * class or introducing a component registry. The engine is
 * {@link Transactions}; this interface adds the typed component view. Rebinding
 * reinstalls editing policies on the draft. Commit uses bound storage so those
 * policies are not applied twice.</p>
 *
 * <p>Transactions rerun domain operations on conflict; they do not invoke incoming
 * merge or fork reconciliation. No lattice definition or publication merge is
 * required. Choose a component containing every value the operation reads or
 * writes, and obtain children from the callback's view. A transaction on an
 * existing fork commits only to that fork; publishing it remains a separate sync.</p>
 *
 * @param <V> Lattice value type
 * @param <C> Domain component type supplied to callbacks
 */
public interface TransactionalComponent<V extends ACell,C> {

	/** Returns bound working storage, before editing policies reinstalled by withCursor. */
	AForkableCursor<V> storage();

	/**
	 * Creates a fresh component over the supplied cursor, retaining domain metadata
	 * and any explicitly owned configuration such as a DLFS store. All mutable child
	 * views must use the supplied cursor; never reuse children bound to the original
	 * live cursor. Do not fork the supplied cursor: the transaction engine reads its
	 * completed value directly. An {@link ALatticeComponent} subclass normally
	 * delegates to its protected {@code rebind(cursor)} hook, sharing construction
	 * with {@code fork()}. Retain configuration, not the source component's working views.
	 */
	C withCursor(AForkableCursor<V> cursor);

	/**
	 * Applies domain operations atomically, retrying conflicts. A transactional root
	 * falls back to serial execution after three failed attempts; an ordinary root
	 * continues optimistically. Equality, rather than Java object identity, determines
	 * whether an input has changed or an operation leaves that input unchanged.
	 */
	default void transact(Consumer<? super C> action) {
		Objects.requireNonNull(action);
		Transactions.transact(storage(),draft -> action.accept(withCursor(draft)));
	}

	/**
	 * Runs the callback once while excluding other writes to the entire shared root.
	 * Ordinary root reads remain available; fork reads share its coordination lock.
	 * Keep callbacks short; do not wait for another operation on the same state,
	 * publish state, or write through a captured live component inside the callback.
	 * An ordinary root throws UnsupportedOperationException before invoking the callback.
	 */
	default void transactLocked(Consumer<? super C> action) {
		Objects.requireNonNull(action);
		Transactions.transactLocked(storage(),draft -> action.accept(withCursor(draft)));
	}

	/**
	 * Schedules {@link #transact} on the shared virtual executor. The future completes
	 * after local commit, or exceptionally on failure. Cancellation does not undo a
	 * commit or guarantee that a running callback stops. No component owns a thread.
	 */
	default CompletableFuture<Void> transactAsync(Consumer<? super C> action) {
		return transactAsync(action,ThreadUtils.getVirtualExecutor());
	}

	/** Uses a caller-supplied executor, including a direct executor if desired. */
	default CompletableFuture<Void> transactAsync(Consumer<? super C> action, Executor executor) {
		Objects.requireNonNull(action);
		return CompletableFuture.runAsync(() -> transact(action),executor);
	}
}
