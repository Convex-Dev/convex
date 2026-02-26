package convex.net.impl.netty;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.SourceCodes;
import convex.core.cpos.CPoSConstants;
import convex.core.data.Blob;
import convex.core.data.Strings;
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
 * @see <a href="https://docs.convex.world/blog/parking-ticket-pattern">The Parking Ticket Pattern</a>
 */
class NettyInboundHandler extends ByteToMessageDecoder {

	static final Logger log = LoggerFactory.getLogger(NettyInboundHandler.class.getName());

	/**
	 * Delivery function. Returns null if accepted, or a blocking retry predicate
	 * if the message could not be queued (backpressure signal).
	 */
	private final Function<Message, Predicate<Message>> deliver;

	/**
	 * Action to send a result back to the client on this channel.
	 */
	private final Predicate<Message> returnAction;

	/**
	 * Connection associated with this handler. Set for server-side inbound channels.
	 */
	private AConnection connection;

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

	/**
	 * Creates a handler for a single inbound channel.
	 *
	 * @param deliver  Dispatch function. Returns null if the message was accepted,
	 *                 or a blocking retry predicate if the queue was full.
	 * @param returnAction Action to send results back to the client on this channel.
	 */
	public NettyInboundHandler(Function<Message, Predicate<Message>> deliver, Predicate<Message> returnAction)  {
		this.deliver=deliver;
		this.returnAction=returnAction;
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		log.debug("Closed Netty channel due to: "+cause.getMessage(),cause);
		ctx.close();
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
	}

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
		// Gate: stop decoding while a message is being retried on a virtual thread.
		// ByteToMessageDecoder sees no progress and stops its loop.
		// Remaining bytes stay in the cumulation buffer, preserving order.
		if (backpressured) return;

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
				if (mlen>CPoSConstants.MAX_MESSAGE_LENGTH) throw new BadFormatException("Message too long: "+mlen);
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
			: Message.create(returnAction,null,Blob.wrap(messageData));
			out.add(m);
			Predicate<Message> retry = deliver.apply(m);
			if (retry != null) {
				// Queue full — pause this channel, retry on virtual thread
				backpressured = true;
				ctx.channel().config().setAutoRead(false);

				Thread.startVirtualThread(() -> {
					try {
						boolean delivered = retry.test(m);
						if (!delivered) {
							// Timeout — return :LOAD error
							Result r = Result.create(m.getID(),
								Strings.SERVER_LOADED, ErrorCodes.LOAD)
								.withSource(SourceCodes.PEER);
							try {
								m.returnResult(r);
							} catch (Exception e) {
								// best effort
							}
						}
					} finally {
						// Resume reading. Order matters: clear the flag first
						// so decode() will process data, then re-enable autoRead.
						backpressured = false;
						ctx.channel().config().setAutoRead(true);

						// Flush data stranded in ByteToMessageDecoder's cumulation
						// buffer. When decode() returned early (backpressured gate),
						// any bytes already read from the socket stay in cumulation.
						// Re-enabling autoRead only helps if NEW data arrives on
						// the socket. To process already-buffered data, we fire a
						// synthetic channelRead with an empty buffer — this triggers
						// ByteToMessageDecoder.callDecode() on the existing cumulation.
						ctx.channel().eventLoop().execute(() -> {
							try {
								ctx.pipeline().fireChannelRead(Unpooled.EMPTY_BUFFER);
							} catch (Exception e) {
								ctx.fireExceptionCaught(e);
							}
						});
					}
				});
			}
		} catch (Throwable e) {
			log.warn("Inbound message handling error: {}",e.getMessage());
			throw e;
		}
	}
}
