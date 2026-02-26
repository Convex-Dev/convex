package convex.core.message;

import java.io.IOException;
import java.net.InetSocketAddress;

import convex.core.data.AccountKey;

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

	private AccountKey trustedKey=null;

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
	 * Sends a message over this connection, blocking until the message can be
	 * queued or a timeout is reached. Safe to call from virtual threads.
	 *
	 * @param msg Message to send
	 * @return true if message queued successfully, false on timeout, full buffer, or closed connection
	 * @throws IOException If IO error occurs while sending
	 */
	public abstract boolean sendMessage(Message msg) throws IOException;

	/**
	 * Tries to send a message without blocking. Returns immediately.
	 *
	 * @param msg Message to send
	 * @return true if message queued successfully, false if the outbound queue is full or connection is closed
	 */
	public boolean trySendMessage(Message msg) {
		try {
			return sendMessage(msg);
		} catch (IOException e) {
			return false;
		}
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
