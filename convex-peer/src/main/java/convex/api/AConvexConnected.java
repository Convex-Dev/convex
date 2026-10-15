package convex.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.SourceCodes;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.data.AccountKey;
import convex.core.data.Strings;
import convex.core.exceptions.BadFormatException;
import convex.core.message.AConnection;
import convex.core.message.Message;
import convex.core.message.MessageType;
import convex.core.util.FutureUtils;

/**
 * Abstract base class for connection-oriented Convex client implementations.
 *
 * <p>Provides shared dispatch logic for clients that communicate via a persistent
 * {@link AConnection}: result decoding and CHALLENGE auto-response. Request correlation belongs to
 * the physical connection. Both network ({@link ConvexRemote}) and
 * local ({@link ConvexLocal}) connections share this infrastructure.</p>
 *
 * <p>{@link ConvexDirect} bypasses messaging entirely and extends {@link Convex}
 * directly.</p>
 */
public abstract class AConvexConnected extends Convex {

	protected static final Logger log = LoggerFactory.getLogger(AConvexConnected.class.getName());

	/**
	 * Current Connection, may be null or a closed connection.
	 */
	protected volatile AConnection connection;

	/**
	 * Handler for reverse DATA_REQUEST messages received from the remote endpoint.
	 * Null means that this client connection grants no remote data access.
	 */
	private volatile Consumer<Message> dataRequestHandler;

	/**
	 * Handler for unsolicited protocol messages received from the remote endpoint.
	 * Null preserves ordinary client behaviour, where messages other than correlated
	 * results, challenges and explicitly enabled DATA_REQUESTs are ignored.
	 */
	private volatile Consumer<Message> unsolicitedMessageHandler;
	private volatile Runnable receiveObserver;

	/** Observes decoded incoming traffic for a connection owner's liveness policy. */
	public void setReceiveObserver(Runnable observer) {
		receiveObserver=observer;
	}

	/** Tests whether decoded traffic belongs to this client's current physical socket. */
	public boolean usesConnection(AConnection source) {
		return source!=null && connection==source;
	}

	/** Whether a correlated request is still awaiting a reply. */
	public boolean hasPendingRequests() {
		AConnection conn=connection;
		return conn!=null && conn.hasPendingRequests();
	}

	private void observeReceive() {
		Runnable observer=receiveObserver;
		if (observer!=null) observer.run();
	}

	protected AConvexConnected(Address address, AKeyPair keyPair) {
		super(address, keyPair);
	}

	/** Interprets a correlated reply using this client's store and transaction policy. */
	private Result decodeResult(Message message,Throwable error,boolean transaction) {
		while (error instanceof CompletionException && error.getCause()!=null) error=error.getCause();
		if (error instanceof IllegalArgumentException) throw new CompletionException(error);
		if (error instanceof RejectedExecutionException) return Result.FULL_CLIENT_BUFFER;
		if (error!=null) return Result.fromException(error).withSource(SourceCodes.COMM);
		try {
			message.getPayload(getStore());
		} catch (BadFormatException badFormat) {
			log.warn("Bad message format in result: {}",badFormat.getMessage());
			return Result.error(ErrorCodes.FORMAT, Strings.create("Bad message format: "+badFormat.getMessage()));
		}
		Result result=message.toResult();
		observeReceive();
		if (transaction) observeTransactionResult(result);
		return result;
	}

	/**
	 * Result handler for Messages received back from a connection.
	 * Delegates result correlation to the originating connection.
	 *
	 * Also auto-responds to server-initiated CHALLENGE messages and delegates
	 * explicitly enabled reverse DATA_REQUEST messages.
	 */
	protected final Consumer<Message> returnMessageHandler = m-> {
		try {
			AConnection source=m.getConnection();
			if (source==null || source!=connection) return;
			if (source.completeRequest(m)) return;

			// Built-in reverse messages are embedded and safe to inspect storelessly.
			// A lattice delta may legitimately reference the node's serving store, so
			// delegate it undecoded for NodeServer acquisition instead of dropping it.
			try {
				m.getPayload(null);
			} catch (Exception e) {
				Consumer<Message> handler = unsolicitedMessageHandler;
				if (handler != null) {
					handler.accept(m);
					return;
				}
				throw e;
			}
			MessageType type = m.getType();
			observeReceive();
			if (type == MessageType.CHALLENGE) {
				AKeyPair kp = keyPair;
				if (kp != null) {
					m.respondToChallenge(kp, null);
				}
				return;
			} else if (type == MessageType.DATA_REQUEST) {
				Consumer<Message> handler = dataRequestHandler;
				if (handler != null) {
					handler.accept(m);
				}
				return;
			}

			Consumer<Message> handler = unsolicitedMessageHandler;
			if (handler != null) handler.accept(m);
		} catch (Exception e) {
			log.warn("Error in return message handler: {}",e.getMessage());
		}
	};

	/**
	 * Sets the handler for DATA_REQUEST messages initiated by the remote endpoint.
	 * This is deliberately separate from {@link #setStore(convex.core.store.AStore)}:
	 * a local acquisition store must not implicitly become remotely readable.
	 *
	 * <p>The handler runs on the connection's receive thread and should hand off any
	 * blocking work.
	 *
	 * @param handler Data request handler, or null to deny reverse data requests
	 */
	public void setDataRequestHandler(Consumer<Message> handler) {
		this.dataRequestHandler = handler;
	}

	/**
	 * Sets a handler for unsolicited messages that are not consumed by the normal
	 * client result, challenge or DATA_REQUEST paths. Lattice nodes use this only
	 * after the remote endpoint has been authenticated, allowing an outbound client
	 * socket to carry reverse lattice propagation without becoming a general server.
	 *
	 * @param handler unsolicited message handler, or null to ignore such messages
	 */
	public void setUnsolicitedMessageHandler(Consumer<Message> handler) {
		this.unsolicitedMessageHandler = handler;
	}

	/**
	 * Sets the current Connection for this client.
	 *
	 * @param conn Connection value to use
	 */
	protected void setConnection(AConnection conn) {
		AConnection curr=this.connection;
		if (curr == conn) return;
		if (curr!=null) close();
		this.connection = conn;
		// A reconnected peer connection keeps the bounds it was given
		if (conn != null && outboundByteLimit > 0) conn.setOutboundLimits(outboundMessageLimit, outboundByteLimit);
	}

	/**
	 * Checks if this Convex client instance has an open connection.
	 *
	 * @return true if connected, false otherwise
	 */
	public boolean isConnected() {
		AConnection c = this.connection;
		return (c != null) && (!c.isClosed());
	}

	@Override
	protected long getNextID() {
		AConnection conn=connection;
		if (conn==null) throw new IllegalStateException("Connection is closed");
		return conn.nextRequestID().longValue();
	}

	@Override
	protected void setVerifiedPeer(AccountKey key) {
		super.setVerifiedPeer(key);
		AConnection c = connection;
		if (c != null) c.setTrustedKey(key);
	}

	@Override
	public CompletableFuture<Result> message(Message m) {
		AConnection conn=connection;
		if (conn==null) {
			return CompletableFuture.completedFuture(Result.CLOSED_CONNECTION);
		}
		return send(conn,m);
	}

	@Override
	public CompletableFuture<Result> request(Message m) {
		AConnection conn=connection;
		if (conn==null) {
			return CompletableFuture.completedFuture(Result.CLOSED_CONNECTION);
		}
		boolean transaction=m.getType()==MessageType.TRANSACT;
		return FutureUtils.handle(conn.request(m,timeout),(reply,error) -> decodeResult(reply,error,transaction));
	}

	private CompletableFuture<Result> send(AConnection conn, Message m) {
		try {
			if (m.getRequestID()==null) {
				boolean sent=conn.sendMessage(m);
				return CompletableFuture.completedFuture(sent ? Result.SENT_MESSAGE : Result.FULL_CLIENT_BUFFER);
			}
			boolean transaction=m.getType()==MessageType.TRANSACT;
			return FutureUtils.handle(conn.sendRequest(m,timeout),(reply,error) -> decodeResult(reply,error,transaction));
		} catch (Exception e) {
			return CompletableFuture.completedFuture(Result.fromException(e).withInfo(Keywords.SOURCE,SourceCodes.COMM));
		}
	}

	@Override
	public boolean trySend(Message msg) {
		AConnection conn = connection;
		if (conn == null) return false;
		return conn.trySendMessage(msg);
	}

	/** Outbound bounds to apply to the current and any later connection; zero if unset. */
	private int outboundMessageLimit;
	private long outboundByteLimit;

	@Override
	public void setOutboundLimits(int messageLimit, long byteLimit) {
		outboundMessageLimit = messageLimit;
		outboundByteLimit = byteLimit;
		AConnection conn = connection;
		if (conn != null) conn.setOutboundLimits(messageLimit, byteLimit);
	}

	@Override
	public void close() {
		AConnection c = this.connection;
		if (c != null) {
			c.close();
			// Also support external transports written before connection-owned tracking.
			c.closeRequests();
		}
		connection = null;
		verifiedPeer = null;
		dataRequestHandler = null;
		unsolicitedMessageHandler = null;
		receiveObserver = null;
	}
}
