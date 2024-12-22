package convex.net;

import java.io.IOException;
import java.net.InetSocketAddress;

import convex.core.data.AccountKey;
import convex.core.message.Message;

public abstract class AConnection {

	
	private AccountKey trustedKey=null;

	public boolean isTrusted() {
		return trustedKey!=null;
	}

	/**
	 * Sends a message over this connection
	 *
	 * @param msg Message to send
	 * @return true if message buffered successfully, false if failed due to full buffer
	 * @throws IOException If IO error occurs while sending
	 */
	public abstract boolean sendMessage(Message m) throws IOException;

	/**
	 * Returns the remote SocketAddress associated with this connection, or null if
	 * not available
	 *
	 * @return An InetSocketAddress if associated, otherwise null
	 */
	public abstract InetSocketAddress getRemoteAddress();

	/**
	 * Sets the trusted remote key for this connection. Only do this f the other side has successfully responded to an authentication challenge
	 * @param key
	 */
	public void setTrustedKey(AccountKey key) {
		this.trustedKey=key;
	}

	/**
	 * Checks if this connection is closed (i.e. the underlying channel is closed)
	 *
	 * @return true if the channel is closed, false otherwise.
	 */
	public abstract boolean isClosed();

	public abstract void close();

	public abstract long getReceivedCount();

}
