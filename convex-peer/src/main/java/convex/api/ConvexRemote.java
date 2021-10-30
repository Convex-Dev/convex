package convex.api;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import convex.core.Result;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Hash;
import convex.core.lang.RT;
import convex.core.store.AStore;
import convex.core.util.Utils;
import convex.net.Connection;

public class ConvexRemote extends Convex {

	protected ConvexRemote(Address address, AKeyPair keyPair) {
		super(address, keyPair);
	}
	
	protected void connectToPeer(InetSocketAddress peerAddress, AStore store) throws IOException, TimeoutException {
		setConnection(Connection.connect(peerAddress, internalHandler, store));
	}

	/**
	 * Sets the current Connection for this Remote Client
	 *
	 * @param conn Connection value to use
	 */
	protected void setConnection(Connection conn) {
		if (this.connection == conn)
			return;
		close();
		this.connection = conn;
	}
	
	/**
	 * Close without affecting the connection
	 */
	public void closeButMaintainConnection() {
		this.connection = null;
		close();
	}
	
	/**
	 * Gets the consensus state from the connected Peer. The acquired state will be a snapshot
	 * of the network global state as calculated by the Peer.
	 * 
	 * SECURITY: Be aware that if this client instance is connected to an untrusted Peer, the
	 * Peer may lie about the latest state. If this is a security concern, the client should
	 * validate the consensus state independently.
	 * 
	 * @return Future for consensus state
	 * @throws TimeoutException If initial status request times out
	 */
	public Future<State> acquireState() throws TimeoutException {
		try {
			Future<Result> sF = requestStatus();
			AVector<ACell> status = sF.get(timeout, TimeUnit.MILLISECONDS).getValue();
			Hash stateHash = RT.ensureHash(status.get(4));

			if (stateHash == null)
				throw new Error("Bad status response from Peer");
			return acquire(stateHash);
		} catch (InterruptedException | ExecutionException | IOException e) {
			throw Utils.sneakyThrow(e);
		}
	}
}
