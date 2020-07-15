package convex.api;

import java.io.IOException;
import java.net.InetSocketAddress;

import convex.core.crypto.AKeyPair;
import convex.core.data.SignedData;
import convex.core.store.Stores;
import convex.net.Connection;

/**
 * Class representing the client API to the Convex network
 */
public class Convex {

	private final InetSocketAddress peerAddress;
	private final AKeyPair keyPair;
	private Connection conn;

	public Convex(InetSocketAddress peerAddress, AKeyPair keyPair) {
		this.peerAddress=peerAddress;
		this.keyPair=keyPair;
	}

	public static Convex connect(InetSocketAddress peerAddress, AKeyPair keyPair) throws IOException {
		Convex convex=new Convex(peerAddress,keyPair);
		convex.connectToPeer();
		return convex;
	}

	private void connectToPeer() throws IOException {
		setConnection(Connection.connect(peerAddress, null, Stores.CLIENT_STORE));
	}
	
	public <T> SignedData<T> signData(T value) {
		return keyPair.signData(value);
	}

	public InetSocketAddress getPeerAddress() {
		return peerAddress;
	}

	public Connection getConnection() {
		return conn;
	}

	public void setConnection(Connection conn) {
		this.conn = conn;
	}
}
