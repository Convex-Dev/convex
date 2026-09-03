package convex.net.impl.netty;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;

import convex.core.message.Message;
import convex.core.message.AConnection;
import convex.core.util.Utils;
import io.netty.channel.Channel;

/**
 * AConnection for server-side inbound Netty channels.
 *
 * Encodes on the caller thread, then offers the message to the server's shared
 * {@link ServerOutboundQueue}. Replies are refused only when that queue's total
 * byte bound, or this connection's share of it, would be exceeded, so a burst
 * of results is absorbed rather than dropped while the channel is briefly
 * unwritable. One instance per accepted client channel.
 */
class NettyServerConnection extends AConnection {

	private Channel channel;
	private final NettyInboundHandler inboundHandler;
	private final ServerOutboundQueue outbound;

	/** Encoded bytes queued for this connection and not yet reported written. */
	private final AtomicLong outboundBytes = new AtomicLong();

	NettyServerConnection(Channel channel, NettyInboundHandler inboundHandler, ServerOutboundQueue outbound) {
		this.channel = channel;
		this.inboundHandler = inboundHandler;
		this.outbound = outbound;
	}

	@Override
	public boolean sendMessage(Message msg) {
		Channel ch = channel;
		if (ch == null || !ch.isActive()) return false;
		// Message encoding can traverse a large cell tree. Do it on the NodeServer
		// dispatcher (or other caller), never lazily in NettyOutboundHandler.
		long length = NettyConnection.encodedLength(msg);
		if (length < 0) return false; // too large for a frame: logged, not sent
		return outbound.offer(this, ch, msg, Utils.checkedInt(length));
	}

	boolean reserveOutbound(int bytes, long limit) {
		long current;
		do {
			current = outboundBytes.get();
			if (current + bytes > limit) return false;
		} while (!outboundBytes.compareAndSet(current, current + bytes));
		return true;
	}

	void releaseOutbound(int bytes) {
		outboundBytes.addAndGet(-bytes);
	}

	long getOutboundBytes() {
		return outboundBytes.get();
	}

	@Override
	public boolean trySendMessage(Message msg) {
		return sendMessage(msg);
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
