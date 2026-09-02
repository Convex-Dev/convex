package convex.net.impl.netty;

import java.net.InetSocketAddress;

import convex.core.message.Message;
import convex.core.message.AConnection;
import io.netty.channel.Channel;

/**
 * AConnection for server-side inbound Netty channels.
 *
	 * Encodes on the caller thread, then uses writeAndFlush() per message. Refuses
	 * writes while the channel is not writable so slow readers cannot build an
	 * unbounded Netty outbound backlog.
 * One instance per accepted client channel.
 */
class NettyServerConnection extends AConnection {

	private Channel channel;
	private final NettyInboundHandler inboundHandler;

	NettyServerConnection(Channel channel, NettyInboundHandler inboundHandler) {
		this.channel = channel;
		this.inboundHandler = inboundHandler;
	}

	@Override
	public boolean sendMessage(Message msg) {
		Channel ch = channel;
		if (ch == null || !ch.isActive() || !ch.isWritable()) return false;
		// Message encoding can traverse a large cell tree. Do it on the NodeServer
		// dispatcher (or other caller), never lazily in NettyOutboundHandler.
		if (NettyConnection.encodedLength(msg)<0) return false; // too large for a frame: logged, not sent
		if (!ch.isActive() || !ch.isWritable()) return false;
		ch.writeAndFlush(msg);
		return true;
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
