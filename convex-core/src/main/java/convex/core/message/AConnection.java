package convex.core.message;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;

import convex.core.data.AccountKey;
import convex.core.data.prim.CVMLong;

/**
 * Abstract base class for connections between Convex network participants.
 *
 * <p>A connection represents a bidirectional communication channel that can send
 * and receive {@link Message} instances. Every inbound {@code Message} carries a
 * reference to the {@code AConnection} it arrived on, enabling the server to:
 * <ul>
 *   <li>Route result messages back to the originator via {@link #sendMessage(Message)}</li>
 *   <li>Check trust status via {@link #isTrusted()} for Belief priority and backpressure</li>
 *   <li>Close misbehaving connections via {@link #close()}</li>
 * </ul>
 *
 * <h2>Trust Model</h2>
 * <p>Connections start untrusted. A connection becomes trusted after the remote
 * peer successfully responds to a challenge/response verification, at which point
 * {@link #setTrustedKey(AccountKey)} is called with the verified key.</p>
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li>{@link LocalConnection} — in-JVM delivery (ConvexLocal, HTTP API)</li>
 *   <li>{@code NettyConnection} — outbound Netty channel (convex-peer)</li>
 *   <li>{@code NettyServerConnection} — inbound Netty channel (convex-peer)</li>
 *   <li>{@code Connection} — NIO channel (convex-peer)</li>
 * </ul>
 */
public abstract class AConnection {

	/** Published across network, verification and dispatcher threads. */
	private volatile AccountKey trustedKey=null;
	private final AtomicLong requestCounter=new AtomicLong();

	/**
	 * Allocates the next request ID originating from this connection.
	 *
	 * <p>Request IDs are a transport correlation concern. Keeping the counter on
	 * the originating connection prevents semantic protocol code from inventing
	 * timestamp or process-wide IDs, and permits independent connections to use
	 * independent ID spaces safely.</p>
	 *
	 * @return next connection-local request ID
	 */
	public final CVMLong nextRequestID() {
		return CVMLong.create(requestCounter.getAndIncrement());
	}

	/**
	 * Checks if this connection has been verified as a trusted peer.
	 * @return true if trusted, false otherwise
	 */
	public boolean isTrusted() {
		return trustedKey!=null;
	}

	/**
	 * Gets the trusted remote key for this connection, or null if not yet verified.
	 * @return AccountKey of the verified remote peer, or null
	 */
	public AccountKey getTrustedKey() {
		return trustedKey;
	}
	
	/**
	 * Sets the trusted remote key for this connection. Only call this after
	 * the remote side has successfully responded to a challenge/response verification.
	 * @param key Verified AccountKey of the remote peer, or null to clear trust
	 */
	public void setTrustedKey(AccountKey key) {
		this.trustedKey=key;
	}

	/**
	 * Returns a result message to the other end of this connection. This is
	 * the non-blocking result delivery path used by server processing threads.
	 *
	 * <p>Default implementation delegates to {@link #trySendMessage(Message)}.
	 * Subclasses may override if result delivery requires different behaviour
	 * from general message sending.</p>
	 *
	 * @param msg Result message to deliver
	 * @return true if delivered successfully, false otherwise
	 */
	public boolean returnMessage(Message msg) {
		return trySendMessage(msg);
	}

	/**
	 * Returns a message to the remote end, waiting a bounded time if the
	 * connection's shared outbound capacity is exhausted. For handler threads that
	 * report client results and may apply backpressure; never call from an I/O
	 * thread. A connection that cannot accept the message for its own reasons
	 * (closed, or its reader is not draining) still refuses at once.
	 *
	 * @param msg Message to return
	 * @return true if the message was accepted for delivery
	 */
	public boolean returnMessageBlocking(Message msg) {
		return returnMessage(msg);
	}

	/**
	 * Checks if this connection supports general message sending (as opposed
	 * to result-only delivery via {@link #returnMessage(Message)}).
	 *
	 * <p>Returns true by default. {@link LocalConnection} returns false for
	 * return-only connection ends. Callers should check this before attempting
	 * server-initiated protocol exchange (e.g. challenge/response).</p>
	 *
	 * @return true if {@link #sendMessage(Message)} is supported
	 */
	public boolean supportsMessage() {
		return true;
	}

	/**
	 * Checks whether this connection's outbound queue is under pressure, i.e. a
	 * substantial share of its capacity is already waiting to be sent. Senders use
	 * this to skip optional traffic for a slow receiver while still offering the
	 * essential messages. Returns false by default.
	 *
	 * @return true if outbound capacity is substantially used
	 */
	public boolean isOutboundBusy() {
		return false;
	}

	/**
	 * Sets the bounds of this connection's outbound queue, for example to buffer far
	 * more for a verified peer than for an ordinary client. No effect by default.
	 *
	 * @param messageLimit Maximum queued messages
	 * @param byteLimit Maximum queued encoded bytes
	 */
	public void setOutboundLimits(int messageLimit, long byteLimit) {
		// no outbound queue by default
	}

	/**
	 * Sends a message over this connection. May block with a bounded timeout
	 * if the outbound queue is full (e.g. outbound client connections under
	 * backpressure). Callers that must not block should use
	 * {@link #trySendMessage(Message)} instead.
	 *
	 * @param msg Message to send
	 * @return true if message queued/sent successfully, false otherwise
	 */
	public abstract boolean sendMessage(Message msg);

	/**
	 * Sends a message without blocking. Returns immediately with false if the
	 * message cannot be queued (buffer full, connection closed, etc.).
	 *
	 * <p>Used by {@link Message#returnMessage(Message)} to deliver results
	 * back to the originator. Server processing threads must never block on
	 * I/O, so result delivery always goes through this method.</p>
	 *
	 * <p>Implementations must guarantee this method never blocks. For queue-based
	 * connections, use a non-blocking offer (no timeout).</p>
	 *
	 * @param msg Message to send
	 * @return true if message queued successfully, false if it could not be sent without blocking
	 */
	public abstract boolean trySendMessage(Message msg);

	/**
	 * Sends a small, replaceable priority message without blocking. Queue-based
	 * transports may coalesce an older unsent priority message so the latest
	 * consensus/control root is not trapped behind bulk propagation data.
	 *
	 * @param msg small priority message
	 * @return true if accepted for delivery
	 */
	public boolean trySendPriorityMessage(Message msg) {
		return trySendMessage(msg);
	}

	/**
	 * Returns the remote socket address associated with this connection, or null if
	 * not available (e.g. for local connections).
	 *
	 * @return An InetSocketAddress if associated, otherwise null
	 */
	public abstract InetSocketAddress getRemoteAddress();

	/**
	 * Checks if this connection is closed.
	 *
	 * @return true if closed, false otherwise
	 */
	public abstract boolean isClosed();

	/**
	 * Closes this connection. Idempotent — safe to call multiple times.
	 */
	public abstract void close();

	/**
	 * Gets the count of messages received on this connection.
	 * @return Number of messages received
	 */
	public abstract long getReceivedCount();

	@Override
	public String toString() {
		InetSocketAddress addr=getRemoteAddress();
		String addrStr=(addr!=null) ? addr.toString() : "local";
		String trust=isTrusted() ? " trusted="+trustedKey : " untrusted";
		return getClass().getSimpleName()+"["+addrStr+trust+"]";
	}
}
