package convex.net.impl.netty;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;

import convex.core.message.Message;
import convex.core.message.AConnection;
import convex.core.util.Utils;
import convex.peer.Config;
import io.netty.channel.Channel;

/**
 * AConnection for server-side inbound Netty channels.
 *
 * <p>Encodes on the caller thread, then offers the message to the server's shared
 * {@link ServerOutboundQueue}. {@link #trySendMessage} and {@link #returnMessage}
 * never wait and are safe on an I/O thread. {@link #sendMessage} and
 * {@link #returnMessageBlocking} wait a bounded time for space in the shared
 * queue, applying backpressure to handler threads that report client results
 * rather than dropping them under load. Neither form waits for this channel
 * itself: a channel that is closed, or already holds more than its cap of bytes
 * handed to Netty and unwritten, has its reply refused at once, so a reader that
 * stalls loses only its own replies. One instance per accepted client channel.</p>
 */
class NettyServerConnection extends AConnection {

	private Channel channel;
	private final NettyInboundHandler inboundHandler;
	private final ServerOutboundQueue outbound;

	/** Encoded bytes handed to Netty for this channel and not yet reported written. */
	private final AtomicLong pendingBytes = new AtomicLong();

	NettyServerConnection(Channel channel, NettyInboundHandler inboundHandler, ServerOutboundQueue outbound) {
		this.channel = channel;
		this.inboundHandler = inboundHandler;
		this.outbound = outbound;
	}

	@Override
	public boolean sendMessage(Message msg) {
		return send(msg, true);
	}

	@Override
	public boolean trySendMessage(Message msg) {
		return send(msg, false);
	}

	@Override
	public boolean returnMessageBlocking(Message msg) {
		return send(msg, true);
	}

	private boolean send(Message msg, boolean mayBlock) {
		Channel ch = channel;
		if (ch == null || !ch.isActive()) return false;
		// Message encoding can traverse a large cell tree. Do it on the caller's
		// thread, never lazily in NettyOutboundHandler.
		long length = NettyConnection.encodedLength(msg);
		if (length < 0) return false; // too large for a frame: logged, not sent
		int bytes = Utils.checkedInt(length);
		if (!mayBlock) return outbound.offer(this, ch, msg, bytes);
		try {
			return outbound.offerBlocking(this, ch, msg, bytes, Config.DEFAULT_INTERNAL_TIMEOUT);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	void addPendingBytes(int delta) {
		pendingBytes.addAndGet(delta);
	}

	long getPendingBytes() {
		return pendingBytes.get();
	}

	@Override
	public InetSocketAddress getRemoteAddress() {
		Channel ch = channel;
		if (ch == null) return null;
		return (InetSocketAddress) ch.remoteAddress();
	}

	@Override
	public boolean isClosed() {
		Channel ch = channel;
		return ch == null || !ch.isOpen();
	}

	@Override
	public void close() {
		Channel ch = channel;
		if (ch != null) {
			ch.close();
			channel = null;
		}
	}

	@Override
	public long getReceivedCount() {
		return inboundHandler.getReceivedCount();
	}
}
