package convex.core.message;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;

/**
 * Abstract base class for connections between Convex network participants.
 *
 * <p>A connection represents a bidirectional communication channel that can send
 * and receive {@link Message} instances. Every inbound {@code Message} carries a
 * reference to the {@code AConnection} it arrived on, enabling the server to:
 * <ul>
 *   <li>Route result messages back to the originator via {@link #sendMessage(Message)}</li>
 *   <li>Check trust status via {@link #isTrusted()} for Belief admission and backpressure</li>
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
	/** Allocated on first request, never replaced during this connection's lifetime. */
	private volatile ConcurrentHashMap<ACell,RequestFuture> awaiting;
	private volatile boolean requestsClosed;

	/** Allocates an ID, registers before sending, and awaits a reply. Timeout is in milliseconds; zero disables it. */
	public final CompletableFuture<Message> request(Message message,long timeout) {
		return request(message,timeout,null);
	}

	/**
	 * As {@link #request(Message, long)}, using a caller-owned future so its owner can
	 * cancel the operation even before a worker starts. The supplied future is a
	 * control signal, not the public reply: observe the returned future, which
	 * guarantees cleanup before notifying its callers. The signal must be fresh.
	 */
	public final CompletableFuture<Message> request(Message message,long timeout,CompletableFuture<Message> future) {
		try {
			Message request=message.withID(nextRequestID());
			if (request==null) throw new IllegalArgumentException("Message type does not support request IDs");
			return sendRequest(request,timeout,future);
		} catch (Exception e) {
			if (future==null) return CompletableFuture.failedFuture(e);
			future.completeExceptionally(e);
			return future;
		}
	}

	/**
	 * Sends with the supplied ID unchanged, for raw-message API compatibility.
	 * Rejects outstanding duplicates. Callers must not reuse an ID while a late
	 * reply to its previous use is possible; use {@link #request(Message, long)} for fresh IDs.
	 */
	public final CompletableFuture<Message> sendRequest(Message message,long timeout) {
		return sendRequest(message,timeout,null);
	}

	private CompletableFuture<Message> sendRequest(Message message,long timeout,CompletableFuture<Message> future) {
		RequestFuture reply=null;
		try {
			if (future!=null && future.isDone()) return future;
			if (requestsClosed || isClosed()) throw new IllegalStateException("Connection closed");
			ACell id=message.getRequestID();
			if (id==null) throw new IllegalArgumentException("Non-null request ID required");
			var pending=awaiting;
			if (pending==null) {
				synchronized (this) {
					pending=awaiting;
					if (pending==null) awaiting=pending=new ConcurrentHashMap<>();
				}
			}
			reply=new RequestFuture(pending,id,future);
			if (pending.putIfAbsent(id,reply)!=null) {
				throw new IllegalStateException("Duplicate outstanding request ID: "+id);
			}
			if (future!=null) reply.listenToControl();
			// Close publishes requestsClosed BEFORE draining. If this insertion missed
			// that drain, this recheck completes it. No registration lock is needed.
			if (requestsClosed) throw new IllegalStateException("Connection closed");
			if (timeout>0) reply.orTimeout(timeout,TimeUnit.MILLISECONDS);
			if (!reply.isDone() && !sendMessage(message)) {
				throw new RejectedExecutionException("Unable to send request");
			}
		} catch (Exception e) {
			if (reply!=null) reply.completeExceptionally(e);
			else {
				if (future==null) return CompletableFuture.failedFuture(e);
				future.completeExceptionally(e);
				return future;
			}
		}
		return reply;
	}

	/** The registered future is also the reply: unlink before CompletableFuture invokes callers. */
	private static final class RequestFuture extends CompletableFuture<Message> {
		private volatile ConcurrentHashMap<ACell,RequestFuture> pending;
		private final ACell id;
		private volatile CompletableFuture<Message> control;

		RequestFuture(ConcurrentHashMap<ACell,RequestFuture> pending,ACell id,CompletableFuture<Message> control) {
			this.pending=pending;
			this.id=id;
			this.control=control;
		}

		// Only authentication workers use an external control signal. Ordinary
		// requests allocate no control future or completion stage.
		void listenToControl() {
			CompletableFuture<Message> signal=control;
			if (signal==null) return; // A concurrent close already completed us.
			signal.whenComplete((message,error) -> {
				if (control==null) return; // Ignore our own notification of the signal.
				if (error==null) complete(message);
				else completeExceptionally(error);
			});
		}

		private CompletableFuture<Message> unlink() {
			var registered=pending;
			if (registered!=null) registered.remove(id,this);
			pending=null;
			var signal=control;
			control=null;
			return signal;
		}

		@Override public boolean complete(Message message) {
			var signal=unlink();
			if (signal!=null) signal.complete(message);
			return super.complete(message);
		}

		@Override public boolean completeExceptionally(Throwable error) {
			java.util.Objects.requireNonNull(error);
			var signal=unlink();
			if (signal!=null) signal.cancel(false);
			return super.completeExceptionally(error);
		}

		@Override public boolean cancel(boolean interrupt) {
			var signal=unlink();
			if (signal!=null) signal.cancel(interrupt);
			return super.cancel(interrupt);
		}
	}

	/** Matches a reply only against the physical connection it arrived on. Does not decode its payload. */
	public final boolean completeRequest(Message message) throws BadFormatException {
		if (message.getConnection()!=this) return false;
		var pending=awaiting;
		if (pending==null) return false;
		ACell id=message.getResultID();
		if (id==null) return false;
		var future=pending.remove(id);
		if (future==null) return false;
		future.pending=null; // Already removed; do not repeat the map lookup on completion.
		future.complete(message);
		return true;
	}

	/** Whether this connection has requests awaiting replies. Does not allocate a map. */
	public final boolean hasPendingRequests() {
		var pending=awaiting;
		return pending!=null && !pending.isEmpty();
	}

	/**
	 * Permanently ends request tracking. Transports must call this on explicit close
	 * AND remote disconnect, outside transport locks: completion can invoke user code.
	 */
	public final void closeRequests() {
		requestsClosed=true;
		var pending=awaiting;
		if (pending==null) return;
		pending.forEach((id,future) -> {
			if (pending.remove(id,future)) {
				future.pending=null;
				future.completeExceptionally(new IllegalStateException("Connection closed"));
			}
		});
	}

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
	 * Updates the encoded inbound message limit. Wire transports must enforce this
	 * before allocating a frame. Unsupported connections fail explicitly.
	 * @param limit maximum encoded message bytes
	 */
	public void setMaxMessageLength(int limit) {
		throw new UnsupportedOperationException("Connection has no configurable receive limit");
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
