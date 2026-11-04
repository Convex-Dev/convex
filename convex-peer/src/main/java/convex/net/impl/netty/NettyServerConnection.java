package convex.net.impl.netty;

import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import convex.core.message.Message;
import convex.core.message.AConnection;
import convex.core.util.Utils;
import io.netty.channel.Channel;

/**
 * AConnection for server-side inbound Netty channels.
 *
 * <p>Encodes on the caller thread, then offers the message to the server's shared
 * {@link ServerOutboundQueue}. No send waits, so every form is safe on any thread:
 * a message is queued, or refused at once if the shared queue is full or this
 * connection's backlog of unwritten replies is over its allowance, so a reader that
 * stalls loses only its own replies. Before that, a client whose backlog grows
 * stops having its requests read until the backlog drains. One instance per
 * accepted client channel.</p>
 */
class NettyServerConnection extends AConnection {

	private Channel channel;
	private final NettyInboundHandler inboundHandler;
	private final ServerOutboundQueue outbound;

	/** Encoded bytes queued for this channel or handed to Netty, and not yet written. */
	private final AtomicLong pendingBytes = new AtomicLong();

	NettyServerConnection(Channel channel, NettyInboundHandler inboundHandler, ServerOutboundQueue outbound) {
		this.channel = channel;
		this.inboundHandler = inboundHandler;
		this.outbound = outbound;
	}

	/**
	 * Queues a message for this channel without waiting.
	 *
	 * @return false at once if the shared queue is full or this connection's backlog
	 *         is over its allowance
	 */
	@Override
	public boolean sendMessage(Message msg) throws ClosedChannelException {
		Channel ch = channel;
		if (ch == null || !ch.isActive()) throw new ClosedChannelException();
		// Message encoding can traverse a large cell tree. Do it on the caller's
		// thread, never lazily in NettyOutboundHandler. Too large for a frame throws.
		int bytes = Utils.checkedInt(msg.getMessageData().count());
		return outbound.offer(this, ch, msg, bytes);
	}

	@Override
	public boolean trySendMessage(Message msg) {
		if (NettyConnection.encodedLength(msg) < 0) return false; // too large for a frame: logged, not sent
		try {
			return sendMessage(msg);
		} catch (ClosedChannelException e) {
			return false;
		}
	}

	/** Adds to this connection's backlog, returning the new backlog. */
	long addPendingBytes(int delta) {
		return pendingBytes.addAndGet(delta);
	}

	long getPendingBytes() {
		return pendingBytes.get();
	}

	/** Whether this connection's requests should not be read until its replies drain. */
	boolean isBacklogged() {
		return outbound.isBacklogged(this);
	}

	/**
	 * Called on any thread when this connection's backlog drains far enough to read
	 * requests again. Resumes reading on the channel's event loop if it was paused.
	 */
	void backlogDrained() {
		if (!inboundHandler.isHolding()) return;
		Channel ch = channel;
		if (ch == null) return;
		try {
			ch.eventLoop().execute(inboundHandler::resumeHeld);
		} catch (RejectedExecutionException e) {
			// event loop shut down: the channel is closed, nothing to resume
		}
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
