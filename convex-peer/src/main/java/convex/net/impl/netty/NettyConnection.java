package convex.net.impl.netty;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.Vectors;
import convex.core.message.Message;
import convex.core.message.MessageType;
import convex.core.util.Shutdown;
import convex.core.util.Utils;
import convex.core.message.AConnection;
import convex.peer.Config;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;

public class NettyConnection extends AConnection {

	static final Logger log = LoggerFactory.getLogger(NettyConnection.class.getName());

	/**
	 * Static client connection worker
	 */
	static volatile EventLoopGroup workerGroup = null;

	static volatile Bootstrap clientBootstrap = null;

	private Channel channel;

	private NettyInboundHandler inboundHandler;

	/**
	 * Bounded outbound message queue. Application threads put messages here;
	 * the Netty event loop drains them to the channel when writable.
	 */
	private final ArrayBlockingQueue<Message> outbound =
		new ArrayBlockingQueue<>(Config.OUTBOUND_QUEUE_SIZE);
	private final Object outboundCapacity=new Object();
	private long outboundBytes;

	/** Latest small priority root, coalesced independently of the bulk queue. */
	private final AtomicReference<Message> priorityOutbound=new AtomicReference<>();

	private NettyConnection(Channel channel, NettyInboundHandler inbound) {
		this.channel = channel;
		this.inboundHandler=inbound;
	}

	protected static EventLoopGroup getEventLoopGroup() {
		if (workerGroup != null)
			return workerGroup;

		synchronized (NettyConnection.class) {
			if (workerGroup != null)
				return workerGroup;
			// Worker group handles NIO I/O for all connections. 2 threads is sufficient
			// since actual message processing happens on virtual threads.
			// Daemon threads allow the JVM to exit when all user threads finish.
			DefaultThreadFactory tf = new DefaultThreadFactory("convex-netty", true);
			workerGroup = new MultiThreadIoEventLoopGroup(2, tf, NioIoHandler.newFactory());

			Shutdown.addHook(Shutdown.CONNECTION, () -> {
				EventLoopGroup wg = workerGroup;
				Bootstrap cb = clientBootstrap;
				workerGroup = null;
				clientBootstrap = null;
				if (wg != null) {
					wg.shutdownGracefully();
				}
			});
			return workerGroup;
		}
	}

	protected static Bootstrap getClientBootstrap() {
		if (clientBootstrap != null)
			return clientBootstrap;

		synchronized (NettyConnection.class) {
			if (clientBootstrap != null)
				return clientBootstrap;
			Bootstrap b = new Bootstrap();
			b.group(getEventLoopGroup());
			b.channel(NioSocketChannel.class);
			b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) Config.DEFAULT_INTERNAL_TIMEOUT);
			b.option(ChannelOption.SO_KEEPALIVE, true);
			b.option(ChannelOption.WRITE_BUFFER_WATER_MARK,
				new WriteBufferWaterMark(32 * 1024, 64 * 1024));

			b.handler(new ChannelInitializer<SocketChannel>() {
				@Override
				public void initChannel(SocketChannel ch) throws Exception {
					// nothing to add, connect will do this
				}
			});

			clientBootstrap = b;
			return clientBootstrap;
		}
	}

	public static NettyConnection connect(SocketAddress sa, Consumer<Message> receiveAction) throws InterruptedException, IOException {
		return connect(sa, receiveAction, (int) convex.core.cpos.CPoSConstants.MAX_MESSAGE_LENGTH);
	}

	/**
	 * Connects with an explicit limit for messages received from the remote endpoint.
	 * The limit is installed before the channel pipeline begins processing traffic, so
	 * there is no post-connect window in which an unverified endpoint gets the larger
	 * protocol default.
	 */
	public static NettyConnection connect(SocketAddress sa, Consumer<Message> receiveAction,
			int maxMessageLength) throws InterruptedException, IOException {
		Bootstrap b = getClientBootstrap();
		ChannelFuture f = b.connect(sa);
		f.await(); // Wait until done

		if (!f.isSuccess()) {
			throw new IOException("Failed to connect to peer at "+sa,f.cause());
		}

		Channel chan = f.channel();
		// Wrap Consumer as Function — client receive path has no backpressure
		Function<Message, Predicate<Message>> deliverFn = m -> {
			receiveAction.accept(m);
			return null; // always accepted
		};
		NettyInboundHandler inbound=new NettyInboundHandler(deliverFn,null,maxMessageLength);

		NettyConnection client = new NettyConnection(chan,inbound);

		// Set connection on inbound handler so received messages can route responses back
		inbound.setConnection(client);

		// Pipeline: writability handler triggers drain, inbound handler decodes, outbound handler encodes
		f.channel().pipeline().addLast(
			new ChannelInboundHandlerAdapter() {
				@Override
				public void channelWritabilityChanged(ChannelHandlerContext ctx) {
					client.doFlush();
					ctx.fireChannelWritabilityChanged();
				}

				@Override
				public void channelInactive(ChannelHandlerContext ctx) {
					// Clear queue to wake any threads blocked on offer(timeout)
					client.clearOutbound();
					client.priorityOutbound.set(null);
					ctx.fireChannelInactive();
				}
			},
			inbound,
			new NettyOutboundHandler()
		);

		return client;
	}

	/** Updates the receive limit, for example after successful peer verification. */
	public void setMaxMessageLength(int limit) {
		inboundHandler.setMaxMessageLength(limit);
	}

	public int getMaxMessageLength() {
		return inboundHandler.getMaxMessageLength();
	}

	/**
	 * Sends a message, blocking until the message can be queued or timeout.
	 * Safe to call from virtual threads.
	 *
	 * <p>This is an outbound client connection, so blocking with a bounded
	 * timeout is acceptable — the caller's virtual thread parks while the
	 * outbound queue drains.</p>
	 */
	@Override
	public boolean sendMessage(Message m) {
		Channel ch = channel;
		if (ch == null || !ch.isActive()) return false;
		long length=encodedLength(m);
		if (length<0) return false;
		int bytes=Utils.checkedInt(length);
		boolean reserved=false;
		try {
			reserved=reserveOutbound(bytes,Config.DEFAULT_INTERNAL_TIMEOUT);
			if (!reserved) return false;
			if (!ch.isActive()) {
				releaseOutbound(bytes);
				return false;
			}
			boolean queued = outbound.offer(m, Config.DEFAULT_INTERNAL_TIMEOUT,
				TimeUnit.MILLISECONDS);
			if (!queued) releaseOutbound(bytes);
			if (queued && !ch.isActive() && outbound.remove(m)) {
				releaseOutbound(bytes);
				queued=false;
			}
			if (queued) flushPending();
			return queued;
		} catch (InterruptedException e) {
			if (reserved) releaseOutbound(bytes);
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/**
	 * Non-blocking send. Returns immediately if the outbound queue is full.
	 */
	@Override
	public boolean trySendMessage(Message m) {
		Channel ch = channel;
		if (ch == null || !ch.isActive()) return false;
		long length=encodedLength(m);
		if (length<0) return false;
		int bytes=Utils.checkedInt(length);
		try {
			if (!reserveOutbound(bytes,0)) return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
		if (!ch.isActive()) {
			releaseOutbound(bytes);
			return false;
		}
		boolean queued = outbound.offer(m);
		if (!queued) releaseOutbound(bytes);
		if (queued && !ch.isActive() && outbound.remove(m)) {
			releaseOutbound(bytes);
			queued=false;
		}
		if (queued) flushPending();
		return queued;
	}

	/**
	 * Encodes a message for sending and returns its length, or -1 (logged) if the
	 * payload cannot fit one legal frame. Nothing is ever sent truncated.
	 */
	static long encodedLength(Message m) {
		try {
			return m.getMessageData().count();
		} catch (IllegalArgumentException e) {
			log.warn("Not sending {} message: {}",m.getType(),e.getMessage());
			return -1;
		}
	}

	@Override
	public boolean trySendPriorityMessage(Message m) {
		Channel ch=channel;
		if (ch==null || !ch.isActive()) return false;
		long size=encodedLength(m);
		if (size<0) return false;
		if (size>Config.PRIORITY_OUTBOUND_MESSAGE_LIMIT) return trySendMessage(m);
		priorityOutbound.set(m);
		if (!ch.isActive()) {
			priorityOutbound.compareAndSet(m,null);
			return false;
		}
		flushPending();
		return true;
	}

	/**
	 * Schedule a drain on the Netty event loop.
	 */
	private void flushPending() {
		Channel ch = channel;
		if (ch == null) return;
		ch.eventLoop().execute(this::doFlush);
	}

	/**
	 * Drain the outbound queue to the channel. Runs on the Netty event loop
	 * — single-threaded, no synchronisation needed.
	 *
	 * Uses write() without flush for each message, then a single flush() at
	 * the end. This coalesces many messages into fewer TCP segments, reducing
	 * syscall overhead dramatically under load.
	 */
	private void doFlush() {
		Channel ch = channel;
		if (ch == null) return;
		int count = 0;
		while (ch.isWritable() && ch.isActive()) {
			Message m=priorityOutbound.getAndSet(null);
			if (m==null) {
				m = outbound.poll();
				if (m!=null) releaseOutbound(Utils.checkedInt(m.getMessageData().count()));
			}
			if (m == null) break;
			ch.write(m);
			count++;
		}
		if (count > 0) {
			ch.flush();
		}
	}

	private boolean reserveOutbound(int bytes, long timeoutMillis) throws InterruptedException {
		long remaining=TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
		long deadline=System.nanoTime()+remaining;
		synchronized (outboundCapacity) {
			while (!hasOutboundCapacity(bytes)) {
				if (remaining<=0) return false;
				TimeUnit.NANOSECONDS.timedWait(outboundCapacity,remaining);
				remaining=deadline-System.nanoTime();
			}
			outboundBytes+=bytes;
			return true;
		}
	}

	private boolean hasOutboundCapacity(int bytes) {
		if (bytes<0) return false;
		if (outboundBytes==0) return bytes<=convex.core.cpos.CPoSConstants.MAX_MESSAGE_LENGTH;
		return outboundBytes+bytes<=Config.OUTBOUND_QUEUE_BYTE_LIMIT;
	}

	private void releaseOutbound(int bytes) {
		synchronized (outboundCapacity) {
			outboundBytes-=bytes;
			if (outboundBytes<0) outboundBytes=0;
			outboundCapacity.notifyAll();
		}
	}

	private void clearOutbound() {
		Message message;
		while ((message=outbound.poll())!=null) {
			releaseOutbound(Utils.checkedInt(message.getMessageData().count()));
		}
	}

	protected ChannelFuture send(Message m) {
		return channel.writeAndFlush(m);
	}

	public static void main(String... args) throws Exception {
		NettyConnection client = connect(new InetSocketAddress("localhost", 8000),m->{
			System.err.println("Client received:" + m);
		});

		client.send(Message.create(MessageType.QUERY,Vectors.of(1,2,3,4))).sync();
	}

	@Override
	public InetSocketAddress getRemoteAddress() {
		if (channel==null) return null;
		return (InetSocketAddress) channel.remoteAddress();
	}

	@Override
	public boolean isClosed() {
		if (channel==null) return true;
		return !channel.isOpen();
	}

	@Override
	public void close() {
		if (channel!=null) {
			channel.close();
			channel=null;
		}
 	}

	@Override
	public long getReceivedCount() {
		return inboundHandler.getReceivedCount();
	}

}
