package convex.api;

import java.io.IOException;
import java.net.InetSocketAddress;

import convex.core.crypto.AKeyPair;
import convex.core.data.SignedData;
import convex.core.store.Stores;
import convex.net.Connection;

/**
 * Class representing the client API to the Convex network
 * 
 * "I'm doing a (free) operating system (just a hobby, won't be big and professional like gnu)"
 * - Linus Torvalds
 */
public class Convex {

	private final AKeyPair keyPair;
	private Connection connection;

	private Convex(InetSocketAddress peerAddress, AKeyPair keyPair) {
		this.keyPair=keyPair;
	}

	/**
	 * Create a Convex client by connecting to the specified Peer using the given key pair
	 *
	 * @param peerAddress Address of Peer
	 * @param keyPair Key pair to use for client transactions
	 * @return New Convex client instance
	 * @throws IOException If connection fails
	 */
	public static Convex connect(InetSocketAddress peerAddress, AKeyPair keyPair) throws IOException {
		Convex convex=new Convex(peerAddress,keyPair);
		convex.connectToPeer(peerAddress);
		return convex;
	}

	private void connectToPeer(InetSocketAddress peerAddress) throws IOException {
		setConnection(Connection.connect(peerAddress, null, Stores.CLIENT_STORE));
	}
	
	/**
	 * Signs a value on behalf of this client.
	 * 
	 * @param <T> Type of value to sign
	 * @param value Value to sign
	 * @return
	 */
	public <T> SignedData<T> signData(T value) {
		return keyPair.signData(value);
	}

	/**
	 * Gets the address for the currently connected peer
	 * @return
	 */
	public InetSocketAddress getPeerAddress() {
		return connection.getRemoteAddress();
	}
	
	/**
	 * Returns true if this Convex client instance has a non-null connection that is open,
	 * false otherwise.
	 * 
	 * @return true if connected, false otherwise
	 */
	public boolean isConnected() {
		Connection c=this.connection;
		return (c!=null)&&(!c.isClosed());
	}

	/**
	 * Gets the underlying Connection instance for this Client. May be null if not connected.
	 * @return Connection instance or null
	 */
	public Connection getConnection() {
		return connection;
	}

	/**
	 * Sets the current Connection for this Client
	 * @param conn Connection value to use
	 */
	private void setConnection(Connection conn) {
		if (this.connection==conn) return;
		disconnect();
		this.connection = conn;
	}

	/**
	 * Disconnects the client from the network.
	 */
	public void disconnect() {
		Connection c=this.connection;
		if (c!=null) {
			c.close();
		}
		connection=null;
	}
}
