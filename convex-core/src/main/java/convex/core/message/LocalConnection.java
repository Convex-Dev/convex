package convex.core.message;

import java.net.InetSocketAddress;
import java.util.function.Predicate;

/**
 * Lightweight {@link AConnection} for in-JVM (local) message delivery.
 *
 * <p>Wraps a result-handler predicate so that every {@link Message} has a
 * uniform {@code AConnection} — {@code Server.processMessage()} can always
 * call {@code m.getConnection().isTrusted()} without null checks.</p>
 *
 * <p>A {@code LocalConnection} is never trusted (it represents an in-JVM
 * client, not a peer). Used by {@code ConvexLocal} and the HTTP REST API.</p>
 */
public class LocalConnection extends AConnection {

	private final Predicate<Message> handler;

	public LocalConnection(Predicate<Message> handler) {
		this.handler = handler;
	}

	@Override
	public boolean sendMessage(Message msg) {
		return handler.test(msg);
	}

	@Override
	public boolean trySendMessage(Message msg) {
		return handler.test(msg);
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
