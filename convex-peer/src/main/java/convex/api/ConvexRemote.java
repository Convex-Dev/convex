package convex.api;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import convex.core.Result;
import convex.core.SourceCodes;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.transactions.ATransaction;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.core.message.Message;
import convex.core.store.AStore;
import convex.net.Transport;
import convex.net.Transports;
import convex.peer.Server;

/**
 * Convex client API implementation for peers accessed over a network connection using the Convex binary peer protocol
 */
public class ConvexRemote extends AConvexConnected {
	private volatile int maxInboundMessageLength = (int) convex.core.cpos.CPoSConstants.MAX_MESSAGE_LENGTH;

	protected InetSocketAddress remoteAddress;
	private URI endpoint;
	private Transport transport;

	protected ConvexRemote(Address address, AKeyPair keyPair) {
		super(address, keyPair);
	}

	@Override
	public InetSocketAddress getHostAddress() {
		return remoteAddress;
	}

	protected void connectToPeer(InetSocketAddress peerAddress) throws IOException, TimeoutException, InterruptedException {
		endpoint=Transports.endpoint(peerAddress);
		transport=Transports.TCP;
		connectToPeer();
	}

	private void connectToPeer() throws IOException, TimeoutException, InterruptedException {
		setConnection(transport.connect(endpoint,returnMessageHandler,maxInboundMessageLength));
		remoteAddress=connection.getRemoteAddress();
	}

	/** The original endpoint, retained with its scheme and path across reconnects. */
	public URI getEndpoint() {
		return endpoint;
	}

	public static ConvexRemote connect(URI endpoint) throws IOException, TimeoutException, InterruptedException {
		return connect(endpoint, (int) convex.core.cpos.CPoSConstants.MAX_MESSAGE_LENGTH);
	}

	/**
	 * Opens a transport for a known peer. TLS authenticates the expected key instead
	 * of the hostname; protocol challenge/response remains a separate step.
	 */
	public static ConvexRemote connect(URI endpoint, AccountKey expectedPeer)
			throws IOException, TimeoutException, InterruptedException {
		return connect(endpoint,Transports.forEndpoint(endpoint,Objects.requireNonNull(expectedPeer)));
	}

	public static ConvexRemote connect(URI endpoint, int maxInboundMessageLength)
			throws IOException, TimeoutException, InterruptedException {
		return connect(endpoint,Transports.forEndpoint(endpoint),maxInboundMessageLength);
	}

	/** Opens a connection using an explicit transport, also used for future reconnects. */
	public static ConvexRemote connect(URI endpoint, Transport transport)
			throws IOException, TimeoutException, InterruptedException {
		return connect(endpoint,transport,(int) convex.core.cpos.CPoSConstants.MAX_MESSAGE_LENGTH);
	}

	public static ConvexRemote connect(URI endpoint, Transport transport, int maxInboundMessageLength)
			throws IOException, TimeoutException, InterruptedException {
		ConvexRemote convex=new ConvexRemote(null,null);
		convex.endpoint=Objects.requireNonNull(endpoint);
		convex.transport=Objects.requireNonNull(transport);
		convex.setMaxInboundMessageLength(maxInboundMessageLength);
		convex.connectToPeer();
		return convex;
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
		try {
			return connect(Transports.endpoint(sa),Transports.NETTY);
		} catch (TimeoutException e) {
			throw new IOException(e);
		}
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
		if (connection!=null) connection.setMaxMessageLength(limit);
		maxInboundMessageLength = limit;
	}

	public int getMaxInboundMessageLength() {
		return maxInboundMessageLength;
	}

	public static ConvexRemote connectNIO(InetSocketAddress sa) throws InterruptedException, IOException, TimeoutException {
		return connect(Transports.endpoint(sa),Transports.NIO);
	}

	public synchronized void reconnect() throws IOException, TimeoutException, InterruptedException {
		close();
		connectToPeer();
	}

	@Override
	public CompletableFuture<Result> transact(SignedData<ATransaction> signed) {
		Message m=Message.createTransaction((CVMLong)null, signed);
		return request(m);
	}

	@Override
	public CompletableFuture<Result> query(ACell query, Address address)  {
		Message m=Message.createQuery((CVMLong)null, query,address);
		return request(m);
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
		Message m=Message.createStatusRequest((CVMLong)null);
		return request(m);
	}

	@Override
	protected CompletableFuture<Result> sendChallenge(SignedData<ACell> data) {
		Message m=Message.createChallenge((CVMLong)null, data);
		return request(m);
	}

	@Override
	public <T extends ACell> CompletableFuture<T> acquire(Hash hash, AStore store) {
		Acquiror acquiror=Acquiror.create(hash, store, this);
		return acquiror.getFuture();
	}

	@Override
	public String toString() {
		return "Remote Convex instance at "+endpoint;
	}

	@Override
	public Server getLocalServer() {
		return null;
	}
}
