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
 * queue, so the threads that report client results wait rather than drop them
 * under load; on an I/O thread they do not wait, since
 * that thread writes for every channel it serves. Neither form waits for this channel
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
		return send(msg, Config.DEFAULT_INTERNAL_TIMEOUT);
	}

	@Override
	public boolean trySendMessage(Message msg) {
		return send(msg, 0);
	}

	@Override
	public boolean returnMessageBlocking(Message msg) {
		return send(msg, Config.DEFAULT_INTERNAL_TIMEOUT);
	}

	@Override
	public boolean returnMessageBlocking(Message msg, long timeoutMillis) {
		return send(msg, timeoutMillis);
	}

	private boolean send(Message msg, long timeoutMillis) {
		Channel ch = channel;
		if (ch == null || !ch.isActive()) return false;
		// Message encoding can traverse a large cell tree. Do it on the caller's
		// thread, never lazily in NettyOutboundHandler.
		long length = NettyConnection.encodedLength(msg);
		if (length < 0) return false; // too large for a frame: logged, not sent
		int bytes = Utils.checkedInt(length);
		if (outbound.offer(this, ch, msg, bytes)) return true;
		if (timeoutMillis <= 0 || NettyConnection.inEventLoop(ch.eventLoop().parent())) return false;
		try {
			return outbound.offerBlocking(this, ch, msg, bytes, timeoutMillis);
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
	public void setMaxMessageLength(int limit) {
		inboundHandler.setMaxMessageLength(limit);
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
		closeRequests();
	}

	@Override
	public long getReceivedCount() {
		return inboundHandler.getReceivedCount();
	}
}
