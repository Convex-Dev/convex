package convex.net.impl.netty;

import java.net.InetSocketAddress;

import convex.core.message.Message;
import convex.core.message.AConnection;
import io.netty.channel.Channel;

/**
 * AConnection for server-side inbound Netty channels.
 *
 * Uses writeAndFlush() per message — simple and correct for result delivery.
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
		if (ch == null || !ch.isActive()) return false;
		ch.writeAndFlush(msg);
		return true;
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
