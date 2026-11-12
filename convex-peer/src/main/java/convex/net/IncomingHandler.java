package convex.net;

import java.util.function.Predicate;

import convex.core.message.Message;

/**
 * Receives requests and unsolicited messages on one accepted connection.
 * The transport completes replies to its outstanding requests first.
 *
 * <p>The handler runs on the network event loop and must not block. A message
 * may still contain unresolved references: delivery does not acquire data or
 * choose a store. Hand off work that may wait to a bounded destination.
 *
 * <p>The transport owns this handler until disconnect. Closing may overlap an
 * outstanding backpressure retry; cleanup must be safe alongside that retry and
 * must not block the event loop. Retries must be bounded and tolerate disconnect.
 * A wrapper must close its delegate. Already accepted work may finish after close.
 */
@FunctionalInterface
public interface IncomingHandler extends AutoCloseable {

	/**
	 * Accepts one message, or supplies a retry without having processed it.
	 *
	 * @param message message carrying its physical connection
	 * @return null when handled (including an explicit rejection), otherwise a
	 *         predicate which the transport invokes once off the event loop while
	 *         reads pause; true means accepted, false reports overload to the sender
	 */
	Predicate<Message> handle(Message message);

	/** Releases this connection's handler state. Called once by the transport. */
	@Override
	default void close() {}
}
