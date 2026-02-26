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
 * <p>Either handler may be null. A null handler means that end does not support
 * {@code sendMessage} from the paired end — it returns false. This naturally
 * prevents server-initiated messaging on return-only connections.</p>
 *
 * @see <a href="../../../../../../convex-peer/docs/MESSAGING.md">MESSAGING.md §3</a>
 */
public class LocalConnection extends AConnection {

	private final Predicate<Message> handler;
	private LocalConnection paired;

	private LocalConnection(Predicate<Message> handler) {
		this.handler = handler;
	}

	/**
	 * Creates a paired bidirectional channel. Returns endA; call
	 * {@link #getPaired()} to get endB.
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
		LocalConnection endA = new LocalConnection(handlerA);
		LocalConnection endB = new LocalConnection(handlerB);
		endA.paired = endB;
		endB.paired = endA;
		return endA;
	}

	/**
	 * Convenience factory for a return-only pair. Returns the end with a null
	 * handler (the "client" end). Sending from the returned end delivers to the
	 * given handler via the paired end.
	 *
	 * <p>Equivalent to {@code createPair(null, handler)}.</p>
	 *
	 * @param handler Handler for messages sent from the returned end
	 * @return The client end of the pair (null handler, sends to the handler)
	 */
	public static LocalConnection create(Predicate<Message> handler) {
		return createPair(null, handler);
	}

	/**
	 * Gets the paired end of this connection.
	 * @return The other end, or null if unpaired
	 */
	public LocalConnection getPaired() {
		return paired;
	}

	/**
	 * Sends a message to the paired end's handler. The message is delivered
	 * with the paired end's connection stamped on it, so the receiver can
	 * return results back through the pair.
	 *
	 * <p>Returns false if the paired end has no handler (return-only connection)
	 * or if no paired end exists.</p>
	 */
	@Override
	public boolean sendMessage(Message msg) {
		LocalConnection p = paired;
		if (p == null || p.handler == null) return false;
		return p.handler.test(msg.withConnection(p));
	}

	@Override
	public boolean trySendMessage(Message msg) {
		return sendMessage(msg);
	}

	@Override
	public boolean returnMessage(Message msg) {
		return sendMessage(msg);
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
