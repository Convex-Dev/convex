package convex.lattice;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import convex.core.data.ACell;
import convex.core.util.ThreadUtils;
import convex.lattice.cursor.ALatticeCursor;

/**
 * Typed, atomic operations on a component's lattice subtree.
 *
 * <p>Callbacks receive an isolated component view. They may run more than once,
 * must use that view for all lattice changes, and must not perform external side
 * effects. Descendants share the private working state. Exceptions discard the
 * attempt. Successful completion means committed locally, not published or durable.
 * A transaction view and its descendants must not escape the callback.</p>
 *
 * <p>Implementations need only provide their cursor and a factory which binds the
 * same domain operations and configuration to a supplied cursor. This interface
 * can also be implemented by application subclasses without changing their base
 * class or introducing a component registry.</p>
 *
 * @param <V> Lattice value type
 * @param <C> Domain component type supplied to callbacks
 */
public interface TransactionalComponent<V extends ACell,C> {

	/** Returns this component's cursor. */
	ALatticeCursor<V> cursor();

	/**
	 * Creates a fresh component over the supplied cursor, retaining domain metadata
	 * and persistence policy. All mutable child views must use the supplied cursor;
	 * never reuse children bound to the original live cursor.
	 */
	C withCursor(ALatticeCursor<V> cursor);

	/**
	 * Applies domain operations atomically, retrying conflicts. A transactional root
	 * falls back to serial execution after three failed attempts; an ordinary root
	 * continues optimistically. Equality, rather than Java object identity, determines
	 * whether an input has changed or an operation leaves that input unchanged.
	 */
	default void transact(Consumer<? super C> action) {
		Objects.requireNonNull(action);
		cursor().transact(draft -> action.accept(withCursor(draft)));
	}

	/**
	 * Runs the callback once while excluding other writes to the entire shared root.
	 * Reads remain available. Keep callbacks short; do not wait for another writer,
	 * publish state, or write through a captured live component inside the callback.
	 * An ordinary root throws UnsupportedOperationException before invoking the callback.
	 */
	default void transactLocked(Consumer<? super C> action) {
		Objects.requireNonNull(action);
		cursor().transactLocked(draft -> action.accept(withCursor(draft)));
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
