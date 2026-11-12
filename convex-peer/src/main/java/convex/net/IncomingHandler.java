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
 * Messages arrive undecoded: {@link Message#getType()} may be unknown and
 * {@link Message#getRequestID()} unavailable until the envelope is decoded with
 * {@link Message#getPayload(convex.core.store.AStore)}. Delegating to the handler
 * returned by
 * {@link convex.node.LatticePropagator#accept(convex.core.message.AConnection)}
 * lets the standard handler decode and acquire data in its serving store.
 *
 * <p>The transport owns this handler until disconnect. Closing may overlap an
 * outstanding backpressure retry; cleanup must be safe alongside that retry and
 * must not block the event loop. Retries must be bounded and tolerate disconnect.
 * A wrapper must close its delegate. Already accepted work may finish after close.
 *
 * @see convex.node.LatticeListener#setConnectionHandler(java.util.function.Function)
 * @see convex.net.impl.netty.NettyServer#setConnectionHandler(java.util.function.Function)
 */
@FunctionalInterface
public interface IncomingHandler extends AutoCloseable {

	/**
	 * Accepts one message, or supplies a retry without having processed it.
	 * Acceptance for processing does not imply a completed merge or durability;
	 * those guarantees belong to the protocol's eventual response.
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
