package convex.core.message;

import java.net.InetSocketAddress;
import java.util.function.Predicate;

/**
 * In-JVM {@link AConnection} implementing a paired bidirectional channel.
 *
 * <p>A {@code LocalConnection} is one end of a paired in-memory channel. Each
 * pair consists of two instances that reference each other. Sending on one end
 * delivers to the other end's handler, with the receiving end's connection
 * stamped on the message:</p>
 *
 * <pre>
 *   endA.sendMessage(msg) → endB receives msg.withConnection(endB)
 *   endB.sendMessage(msg) → endA receives msg.withConnection(endA)
 * </pre>
 *
 * <p>The receiver sees its own end on the message. Calling
 * {@code msg.returnMessage(result)} sends through that end, which delivers
 * to the original sender's handler. This is the in-memory equivalent of a
 * shared network socket.</p>
 *
 * <p>Each end has an {@code acceptsMessages} flag set at creation time.
 * When false, the paired end's {@link #sendMessage} returns false, but
 * {@link #returnMessage} still works. This structurally prevents
 * server-initiated protocol exchange (e.g. CHALLENGE) on return-only
 * connections while preserving result delivery.</p>
 *
 * @see <a href="../../../../../../convex-peer/docs/MESSAGING.md">MESSAGING.md §3</a>
 */
public class LocalConnection extends AConnection {

	private final Predicate<Message> handler;
	private final boolean acceptsMessages;
	private LocalConnection paired;

	private LocalConnection(Predicate<Message> handler, boolean acceptsMessages) {
		this.handler = handler;
		this.acceptsMessages = acceptsMessages;
	}

	/**
	 * Creates a paired bidirectional channel. Both ends accept general
	 * messages. Returns endA; call {@link #getPaired()} to get endB.
	 *
	 * <p>{@code handlerA} receives messages sent <em>to</em> endA (from endB).
	 * {@code handlerB} receives messages sent <em>to</em> endB (from endA).
	 * Either handler may be null (return-only in that direction).</p>
	 *
	 * @param handlerA Handler for messages arriving at endA, or null
	 * @param handlerB Handler for messages arriving at endB, or null
	 * @return endA of the paired connection
	 */
	public static LocalConnection createPair(Predicate<Message> handlerA, Predicate<Message> handlerB) {
		LocalConnection endA = new LocalConnection(handlerA, true);
		LocalConnection endB = new LocalConnection(handlerB, true);
		endA.paired = endB;
		endB.paired = endA;
		return endA;
	}

	/**
	 * Convenience factory for a return-only pair. Returns the client end.
	 * The client end has a null handler and does not accept general messages.
	 * Sending from the returned end delivers to the given handler via the
	 * paired end.
	 *
	 * <p>Equivalent to creating a pair where the client end has no handler
	 * and {@code acceptsMessages = false}.</p>
	 *
	 * @param handler Handler for messages sent from the returned end
	 * @return The client end of the pair
	 */
	public static LocalConnection create(Predicate<Message> handler) {
		LocalConnection endA = new LocalConnection(null, false);
		LocalConnection endB = new LocalConnection(handler, true);
		endA.paired = endB;
		endB.paired = endA;
		return endA;
	}

	/**
	 * Creates a paired channel where endA receives results only (not general
	 * messages). Both ends have handlers but endA's {@code acceptsMessages}
	 * is false, so the server end cannot {@link #sendMessage} to endA.
	 * {@link #returnMessage} still works.
	 *
	 * @param handlerA Handler for results arriving at endA
	 * @param handlerB Handler for messages arriving at endB
	 * @return endA of the paired connection
	 */
	public static LocalConnection createReturnable(Predicate<Message> handlerA, Predicate<Message> handlerB) {
		LocalConnection endA = new LocalConnection(handlerA, false);
		LocalConnection endB = new LocalConnection(handlerB, true);
		endA.paired = endB;
		endB.paired = endA;
		return endA;
	}

	/**
	 * Gets the paired end of this connection.
	 * @return The other end, or null if unpaired
	 */
	public LocalConnection getPaired() {
		return paired;
	}

	@Override
	public boolean supportsMessage() {
		LocalConnection p = paired;
		return p != null && p.acceptsMessages;
	}

	/**
	 * Sends a message to the paired end's handler. The message is delivered
	 * with the paired end's connection stamped on it, so the receiver can
	 * return results back through the pair.
	 *
	 * <p>Returns false if the paired end does not accept general messages
	 * ({@code acceptsMessages = false}), has no handler, or no paired end
	 * exists.</p>
	 */
	@Override
	public boolean sendMessage(Message msg) {
		LocalConnection p = paired;
		if (p == null || p.handler == null || !p.acceptsMessages) return false;
		return p.handler.test(msg.withConnection(p));
	}

	@Override
	public boolean trySendMessage(Message msg) {
		return sendMessage(msg);
	}

	/**
	 * Returns a result to the paired end's handler. Always works if the
	 * paired end exists and has a handler, regardless of the
	 * {@code acceptsMessages} flag.
	 */
	@Override
	public boolean returnMessage(Message msg) {
		LocalConnection p = paired;
		if (p == null || p.handler == null) return false;
		return p.handler.test(msg.withConnection(p));
	}

	@Override
	public InetSocketAddress getRemoteAddress() {
		return null;
	}

	@Override
	public boolean isClosed() {
		return false;
	}

	@Override
	public void close() {
		// nothing to close for in-JVM connection
	}

	@Override
	public long getReceivedCount() {
		return 0;
	}
}
