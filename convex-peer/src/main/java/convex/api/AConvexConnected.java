package convex.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.SourceCodes;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Strings;
import convex.core.exceptions.BadFormatException;
import convex.core.message.AConnection;
import convex.core.message.Message;
import convex.core.message.MessageType;

/**
 * Abstract base class for connection-oriented Convex client implementations.
 *
 * <p>Provides shared dispatch logic for clients that communicate via a persistent
 * {@link AConnection}: result correlation (awaiting map), register-before-send
 * pattern, and CHALLENGE auto-response. Both network ({@link ConvexRemote}) and
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
	protected AConnection connection;

	/**
	 * Map of results awaiting completion. ConcurrentHashMap allows lock-free access
	 * from both client threads (put/remove) and network I/O threads (get/complete).
	 */
	private final ConcurrentHashMap<ACell, CompletableFuture<Message>> awaiting = new ConcurrentHashMap<>();

	protected AConvexConnected(Address address, AKeyPair keyPair) {
		super(address, keyPair);
	}

	/**
	 * Registers a future to await a return Message for the given result ID.
	 * Must be called BEFORE sending the message to prevent a race where the
	 * response arrives before the future is registered.
	 *
	 * @param resultID ID of result message to await
	 * @param timeout Timeout in milliseconds, or 0 for no timeout
	 * @return CompletableFuture for the Result
	 */
	private CompletableFuture<Result> awaitResult(ACell resultID, long timeout) {
		if (resultID==null) throw new IllegalArgumentException("Non-null return ID required");

		CompletableFuture<Message> cf = new CompletableFuture<Message>();
		awaiting.put(resultID, cf);

		if (timeout>0) {
			cf=cf.orTimeout(timeout, TimeUnit.MILLISECONDS);
		}
		CompletableFuture<Result> cr=cf.handle((m,e)->{
			awaiting.remove(resultID);

			if (e!=null) {
				sequence=null;
				return Result.fromException(e);
			}

			try {
				m.getPayload(getStore());
			} catch (BadFormatException e1) {
				log.warn("Bad message format in result: {}",e1.getMessage());
				sequence=null;
				return Result.error(ErrorCodes.FORMAT, Strings.create("Bad message format: "+e1.getMessage()));
			}
			Result r=m.toResult();
			if (r.getErrorCode()!=null) {
				sequence=null;
			}
			return r;
		});
		return cr;
	}

	/**
	 * Result handler for Messages received back from a connection.
	 * Completes the awaiting future for result correlation.
	 *
	 * Also auto-responds to server-initiated CHALLENGE messages so that
	 * inbound connections can be verified by the remote peer.
	 */
	protected final Consumer<Message> returnMessageHandler = m-> {
		try {
			// Fast path: RESULT messages (the common case)
			ACell id=m.getResultID();
			if (id!=null) {
				CompletableFuture<Message> cf = awaiting.remove(id);
				if (cf != null) {
					cf.complete(m);
				}
				return;
			}

			// Non-RESULT message — check for server-initiated CHALLENGE
			m.getPayload(null);
			if (m.getType() == MessageType.CHALLENGE) {
				AKeyPair kp = keyPair;
				if (kp != null) {
					m.respondToChallenge(kp, null);
				}
			}
		} catch (Exception e) {
			log.warn("Error in return message handler: {}",e.getMessage());
		}
	};

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

		ACell id=m.getRequestID();
		try {
			if (id==null) {
				// Not expecting any return message, so just report sending
				boolean sent = conn.sendMessage(m);
				if (!sent) return CompletableFuture.completedFuture(Result.FULL_CLIENT_BUFFER);
				return CompletableFuture.completedFuture(Result.SENT_MESSAGE);
			}

			// Register future BEFORE send — response handler can find it immediately
			CompletableFuture<Result> cf = awaitResult(id, timeout);
			boolean sent = conn.sendMessage(m);
			if (!sent) {
				awaiting.remove(id);
				return CompletableFuture.completedFuture(Result.FULL_CLIENT_BUFFER);
			}
			return cf;
		} catch (Exception e) {
			if (id!=null) awaiting.remove(id);
			Result r=Result.fromException(e).withInfo(Keywords.SOURCE,SourceCodes.COMM);
			return CompletableFuture.completedFuture(r);
		}
	}

	@Override
	public boolean trySend(Message msg) {
		AConnection conn = connection;
		if (conn == null) return false;
		return conn.trySendMessage(msg);
	}

	@Override
	public void close() {
		AConnection c = this.connection;
		if (c != null) {
			c.close();
		}
		connection = null;
		verifiedPeer = null;
		awaiting.clear();
	}
}
