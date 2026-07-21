package convex.api;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import convex.core.Result;
import convex.core.SourceCodes;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.transactions.ATransaction;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.SignedData;
import convex.core.message.Message;
import convex.core.store.AStore;
import convex.net.impl.netty.NettyConnection;
import convex.net.impl.nio.Connection;
import convex.peer.Config;
import convex.peer.Server;

/**
 * Convex client API implementation for peers accessed over a network connection using the Convex binary peer protocol
 */
public class ConvexRemote extends AConvexConnected {
	private volatile int maxInboundMessageLength = (int) convex.core.cpos.CPoSConstants.MAX_MESSAGE_LENGTH;

	protected InetSocketAddress remoteAddress;

	protected ConvexRemote(Address address, AKeyPair keyPair) {
		super(address, keyPair);
	}

	@Override
	public InetSocketAddress getHostAddress() {
		return remoteAddress;
	}

	protected void connectToPeer(InetSocketAddress peerAddress) throws IOException, TimeoutException, InterruptedException {
		remoteAddress=peerAddress;
		if (Config.USE_NETTY_CLIENT) {
			setConnection(NettyConnection.connect(peerAddress, returnMessageHandler, maxInboundMessageLength));
		} else {
			setConnection(Connection.connect(peerAddress, returnMessageHandler, maxInboundMessageLength));
		}
	}

	public static ConvexRemote connect(InetSocketAddress peerAddress) throws IOException, TimeoutException, InterruptedException {
		ConvexRemote convex=new ConvexRemote(null,null);
		convex.connectToPeer(peerAddress);
		return convex;
	}

	/**
	 * Connects with an explicit limit for messages received from the remote endpoint.
	 * Node-managed peer connections use this overload to apply their untrusted limit
	 * before the identity challenge is sent.
	 */
	public static ConvexRemote connect(InetSocketAddress peerAddress, int maxInboundMessageLength)
			throws IOException, TimeoutException, InterruptedException {
		ConvexRemote convex=new ConvexRemote(null,null);
		convex.setMaxInboundMessageLength(maxInboundMessageLength);
		convex.connectToPeer(peerAddress);
		return convex;
	}

	public static ConvexRemote connectNetty(InetSocketAddress sa) throws InterruptedException, IOException {
		ConvexRemote convex=new ConvexRemote(null,null);
		convex.remoteAddress=sa;
		convex.setConnection(NettyConnection.connect(sa, convex.returnMessageHandler));
		return convex;
	}

	/**
	 * Sets the encoded-message limit for this connection and future reconnects.
	 * Increasing this value does not confer trust by itself; NodeServer only calls it
	 * with the trusted limit after challenge/response verification succeeds.
	 */
	public synchronized void setMaxInboundMessageLength(int limit) {
		if (limit <= 0 || limit > convex.core.cpos.CPoSConstants.MAX_MESSAGE_LENGTH) {
			throw new IllegalArgumentException("Inbound message limit must be between 1 and "
				+ convex.core.cpos.CPoSConstants.MAX_MESSAGE_LENGTH + ": " + limit);
		}
		maxInboundMessageLength = limit;
		if (connection instanceof NettyConnection netty) {
			netty.setMaxMessageLength(limit);
		} else if (connection instanceof Connection nio) {
			nio.setMaxMessageLength(limit);
		}
	}

	public int getMaxInboundMessageLength() {
		return maxInboundMessageLength;
	}

	public static ConvexRemote connectNIO(InetSocketAddress sa) throws InterruptedException, IOException, TimeoutException {
		ConvexRemote convex=new ConvexRemote(null,null);
		convex.remoteAddress=sa;
		convex.setConnection(Connection.connect(sa, convex.returnMessageHandler));
		return convex;
	}

	public synchronized void reconnect() throws IOException, TimeoutException, InterruptedException {
		close();
		connectToPeer(remoteAddress);
	}

	@Override
	public CompletableFuture<Result> transact(SignedData<ATransaction> signed) {
		Message m=Message.createTransaction(getNextID(), signed);
		return message(m);
	}

	@Override
	public CompletableFuture<Result> query(ACell query, Address address)  {
		Message m=Message.createQuery(getNextID(), query,address);
		return message(m);
	}

	@Override
	public CompletableFuture<Result> messageRaw(Blob rawData) {
		try {
			Message m=Message.create(rawData);
			m.getPayload(getStore());
			return message(m);
		} catch (Exception e) {
			return CompletableFuture.completedFuture(
				Result.fromException(e).withSource(SourceCodes.CLIENT));
		}
	}

	@Override
	public CompletableFuture<Result> requestStatus() {
		Message m=Message.createStatusRequest(getNextID());
		return message(m);
	}

	@Override
	protected CompletableFuture<Result> sendChallenge(SignedData<ACell> data) {
		Message m=Message.createChallenge(getNextID(), data);
		return message(m);
	}

	@Override
	public <T extends ACell> CompletableFuture<T> acquire(Hash hash, AStore store) {
		Acquiror acquiror=Acquiror.create(hash, store, this);
		return acquiror.getFuture();
	}

	@Override
	public String toString() {
		return "Remote Convex instance at "+getHostAddress();
	}

	@Override
	public Server getLocalServer() {
		return null;
	}
}
