package convex.net.impl.netty;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Result;
import convex.core.cpos.CPoSConstants;
import convex.core.data.Blob;
import convex.core.exceptions.BadFormatException;
import convex.core.message.AConnection;
import convex.core.message.Message;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

/**
 * Decodes inbound messages from a single client channel and delivers them to the server.
 *
 * <p>One instance is created per inbound connection. Implements per-channel backpressure:
 * when the server cannot accept a message (queue full), this handler pauses reads on
 * its channel and retries on a virtual thread. Only the affected client is throttled —
 * all other clients continue at full speed.
 *
 * <p>The backpressure mechanism leverages TCP flow control: pausing socket reads causes
 * the kernel receive buffer to fill, which triggers a TCP zero-window advertisement
 * back to the sender. This naturally slows the client without any application-level
 * error codes or retry logic.
 *
 * <p>On a server channel, reads also pause while the client's replies are backed up
 * ({@link NettyServerConnection#isBacklogged()}): the next decoded message is held
 * back, and delivered when the backlog drains, which resumes reading. No thread waits
 * for that; the write completions that drain the backlog resume it. Pausing always
 * holds a decoded message, so that the decoder does not ask the socket for more input
 * while reads are off.
 *
 * @see <a href="https://docs.convex.world/blog/parking-ticket-pattern">The Parking Ticket Pattern</a>
 */
class NettyInboundHandler extends ByteToMessageDecoder {

	static final Logger log = LoggerFactory.getLogger(NettyInboundHandler.class.getName());

	/**
	 * Delivery function. Returns null if accepted, or a blocking retry predicate
	 * if the message could not be queued (backpressure signal).
	 */
	private final Function<Message, Predicate<Message>> deliver;

	/** Per-server maximum encoded message length, checked before full allocation. */
	private volatile int maxMessageLength;

	/**
	 * Connection associated with this handler. Set for server-side inbound channels.
	 */
	private AConnection connection;

	/** No-op disconnect action, used as the default and the null-reset value. */
	private static final Consumer<AConnection> NO_DISCONNECT = c -> {};

	/**
	 * Action invoked when this channel goes inactive (closes), so the server can release
	 * per-connection state eagerly (#566). Default no-op.
	 */
	private Consumer<AConnection> onDisconnect = NO_DISCONNECT;

	/**
	 * Count of complete messages decoded on this channel.
	 */
	private long receivedCount=0;

	/**
	 * Per-channel backpressure flag. When true, decode() returns without consuming
	 * bytes, stopping the ByteToMessageDecoder loop. This preserves message ordering
	 * — remaining bytes stay in the cumulation buffer and are decoded in order after
	 * the retry completes.
	 */
	private volatile boolean backpressured = false;

	/** The server connection whose reply backlog can pause reads, or null for a client channel. */
	private NettyServerConnection replies;

	/**
	 * A decoded message held back while this channel's replies are backed up, delivered
	 * when they drain. Written on the event loop; read by the thread that drains them.
	 */
	private volatile Message held;

	/** Context of the decode that held {@link #held}. Event loop only. */
	private ChannelHandlerContext heldContext;

	/**
	 * Set once decoding has thrown. A framing error leaves the reader mid-message with no
	 * way to resynchronise, and ByteToMessageDecoder still runs a final decode pass over
	 * the leftover buffer when the channel closes — without this, the body of a rejected
	 * frame gets reinterpreted as a stream of frames and delivered as garbage messages.
	 */
	private boolean failed = false;

	/**
	 * Creates a handler for a single inbound channel.
	 *
	 * @param deliver  Dispatch function. Returns null if the message was accepted,
	 *                 or a blocking retry predicate if the queue was full.
	 * @param returnAction Unused, kept for compatibility (will be removed)
	 */
	public NettyInboundHandler(Function<Message, Predicate<Message>> deliver, Predicate<Message> returnAction)  {
		this(deliver, returnAction, (int) CPoSConstants.MAX_MESSAGE_LENGTH);
	}

	public NettyInboundHandler(Function<Message, Predicate<Message>> deliver,
			Predicate<Message> returnAction, int maxMessageLength) {
		this.deliver=deliver;
		setMaxMessageLength(maxMessageLength);
	}

	/**
	 * Updates the encoded-message limit for this channel. The field is volatile because
	 * peer verification completes off the Netty event loop: an outbound connection starts
	 * with the conservative untrusted limit and may be promoted after its key is verified.
	 *
	 * @param limit maximum encoded body length in bytes
	 */
	void setMaxMessageLength(int limit) {
		if (limit <= 0) throw new IllegalArgumentException("Maximum message length must be positive");
		maxMessageLength = limit;
	}

	int getMaxMessageLength() {
		return maxMessageLength;
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		log.debug("Closed Netty channel due to: "+cause.getMessage(),cause);
		ctx.close();
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		// Deliver any final decoded messages / run decoder cleanup first, then fire the
		// disconnect callback so the server can release per-connection state (#566).
		try {
			super.channelInactive(ctx);
		} finally {
			AConnection conn = connection;
			if (conn != null) {
				conn.closeRequests();
				try {
					onDisconnect.accept(conn);
				} catch (Exception e) {
					log.debug("Disconnect action failed: {}", e.getMessage());
				}
			}
		}
	}

	public long getReceivedCount() {
		return receivedCount;
	}

	/**
	 * Sets the AConnection for this handler. Messages decoded on this channel
	 * will carry this connection for return routing and trust checks.
	 * @param conn Connection to associate with decoded messages
	 */
	void setConnection(AConnection conn) {
		this.connection = conn;
		this.replies = (conn instanceof NettyServerConnection server) ? server : null;
	}

	/**
	 * Sets the action invoked when this channel closes (#566).
	 * @param action Disconnect action (null resets to a no-op)
	 */
	void setDisconnectAction(Consumer<AConnection> action) {
		this.onDisconnect = (action != null) ? action : NO_DISCONNECT;
	}

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
		// Gate: stop decoding while a message is being retried on a virtual thread, or
		// held until replies drain. ByteToMessageDecoder sees no progress and stops its
		// loop. Remaining bytes stay in the cumulation buffer, preserving order.
		if (failed) {
			in.skipBytes(in.readableBytes());
			return;
		}
		if (backpressured || held != null) return;

		try {
	   		in.markReaderIndex(); // mark reader position, may revert to this if no complete message

	   		int mlen=0;
			int newBytes=in.readableBytes();
			if (newBytes==0) return;

			for (int i=0; ; i++) {
				if (i>=newBytes) {
					// insufficient bytes for message length, need to wait for more
					in.resetReaderIndex();
					return;
				}

				byte b=in.readByte();

				if ((i==0)&&(b==0x80)) {
					byte[] bytes=new byte[newBytes];
					bytes[0]=b;
					in.readBytes(bytes, 1, newBytes-1);
					Blob tmp=Blob.wrap(bytes);
					throw new BadFormatException("Zero leading bits in message length, content: "+tmp);
				}

	   			int bm=(b&0x7f); // new bits for length
	   		    mlen=(mlen<<7)+bm;
				if (mlen>maxMessageLength) throw new BadFormatException("Message too long: "+mlen);
				if ((b&0x80)==0) {
					// we have a complete message length
					break;
				}
			}

			if (in.readableBytes()<mlen) {
				// insufficient bytes for message, need to wait for more
				in.resetReaderIndex();
				return;
			}

			byte[] messageData=new byte[mlen];

			// We now have a complete message!
			in.readBytes(messageData);
			receivedCount++;

			AConnection conn=connection;
		Message m = (conn!=null)
			? Message.create(conn, Blob.wrap(messageData))
			: Message.create(Blob.wrap(messageData));
			out.add(m);
			NettyServerConnection r = replies;
			if (r != null && r.isBacklogged()) {
				// Replies backed up: hold this message and stop reading until they drain.
				// Adding it to out above keeps the decoder from asking for more input.
				heldContext = ctx;
				held = m;
				ctx.channel().config().setAutoRead(false);
				if (r.isBacklogged()) return;
				// Drained meanwhile, by a release that may not have seen the held message
				held = null;
				ctx.channel().config().setAutoRead(true);
			}
			dispatch(ctx, m);
		} catch (Throwable e) {
			failed = true;
			in.skipBytes(in.readableBytes());
			if (e instanceof BadFormatException) {
				// Malformed input from a remote client is an expected event on a
				// public port (exceptionCaught closes the channel); debug level so
				// hostile clients cannot spam the operator log. Sustained abuse is
				// surfaced by the #566 circuit-breaker instead.
				log.debug("Rejecting malformed inbound message: {}",e.getMessage());
			} else {
				log.warn("Inbound message handling error: {}",e.getMessage());
			}
			throw e;
		}
	}

	/**
	 * Delivers a decoded message. If the server cannot take it yet, pauses reading and
	 * retries on a virtual thread, resuming reading on the event loop when done.
	 * Event loop only.
	 */
	private void dispatch(ChannelHandlerContext ctx, Message m) {
		Predicate<Message> retry = deliver.apply(m);
		if (retry == null) return;
		// Queue full — pause this channel, retry on virtual thread
		backpressured = true;
		ctx.channel().config().setAutoRead(false);

		Thread.startVirtualThread(() -> {
			try {
				boolean delivered = retry.test(m);
				if (!delivered) {
					// Timeout — return :LOAD error
					try {
						m.returnResult(Result.SERVER_LOADED);
					} catch (Exception e) {
						// best effort
					}
				}
			} catch (RuntimeException | StackOverflowError e) {
				log.debug("Incoming message retry failed; closing connection",e);
				ctx.close();
			} finally {
				ctx.channel().eventLoop().execute(() -> {
					backpressured = false;
					if (ctx.channel().isActive()) resumeReading(ctx);
				});
			}
		});
	}

	/** Whether a message is held back until this channel's replies drain. */
	boolean isHolding() {
		return held != null;
	}

	/**
	 * Delivers the held message and resumes reading, unless the replies are backed up
	 * again, in which case a later drain resumes. Event loop only.
	 */
	void resumeHeld() {
		Message m = held;
		if (m == null || replies.isBacklogged()) return;
		ChannelHandlerContext ctx = heldContext;
		held = null;
		dispatch(ctx, m);
		if (!backpressured) resumeReading(ctx);
	}

	/**
	 * Re-enables reading and decodes data stranded in ByteToMessageDecoder's cumulation
	 * buffer: bytes already read from the socket stay there while decoding is paused,
	 * and re-enabling autoRead only helps if NEW data arrives on the socket. A
	 * synthetic channelRead with an empty buffer triggers callDecode() on the existing
	 * cumulation. It goes to this handler directly: fired from the pipeline head, a TLS
	 * handler would consume it as ciphertext. Event loop only.
	 */
	private void resumeReading(ChannelHandlerContext ctx) {
		ctx.channel().config().setAutoRead(true);
		try {
			channelRead(ctx, Unpooled.EMPTY_BUFFER);
		} catch (Exception e) {
			ctx.fireExceptionCaught(e);
		}
	}
}
