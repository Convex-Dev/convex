package convex.net.impl.netty;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.Vectors;
import convex.core.message.BoundedMessageQueue;
import convex.core.message.Message;
import convex.core.message.MessageType;
import convex.core.util.Shutdown;
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
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.DefaultThreadFactory;

public class NettyConnection extends AConnection {

	static final Logger log = LoggerFactory.getLogger(NettyConnection.class.getName());

	/**
	 * Static client connection worker
	 */
	static volatile EventLoopGroup workerGroup = null;

	static volatile Bootstrap clientBootstrap = null;

	private volatile Channel channel;

	private NettyInboundHandler inboundHandler;

	/**
	 * Bounded outbound message queue. Application threads put messages here; the
	 * Netty event loop drains them to the channel while it is writable, so encoded
	 * messages stay shared on the heap until they are about to enter the socket
	 * buffer. Client bounds by default; {@link #setOutboundLimits} raises them for a
	 * connection to a Peer. The last message admitted may take the queue over its
	 * byte bound, so a large update is never refused merely because the queue is
	 * nearly full.
	 */
	private final BoundedMessageQueue outbound = new BoundedMessageQueue(
		Config.OUTBOUND_QUEUE_SIZE, Config.OUTBOUND_QUEUE_BYTE_LIMIT, true);

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
		return connect(sa, receiveAction, maxMessageLength, null);
	}

	/** Connects using TLS when a context is supplied, waiting for its handshake. */
	public static NettyConnection connect(SocketAddress sa, Consumer<Message> receiveAction,
			int maxMessageLength, SSLContext sslContext) throws InterruptedException, IOException {
		return connect(sa,receiveAction,maxMessageLength,sslContext,true);
	}

	/** Disable hostname checks only when the context authenticates an expected peer key. */
	public static NettyConnection connect(SocketAddress sa, Consumer<Message> receiveAction,
			int maxMessageLength, SSLContext sslContext, boolean verifyHostname) throws InterruptedException, IOException {
		Function<Message, Predicate<Message>> deliverFn = m -> {
			receiveAction.accept(m);
			return null;
		};
		NettyInboundHandler inbound=new NettyInboundHandler(deliverFn,null,maxMessageLength);
		NettyConnection client=new NettyConnection(null,inbound);
		inbound.setConnection(client);
		SslHandler ssl;
		if (sslContext==null) {
			ssl=null;
		} else {
			InetSocketAddress address=(InetSocketAddress)sa;
			SSLEngine engine=sslContext.createSSLEngine(address.getHostString(),address.getPort());
			engine.setUseClientMode(true);
			SSLParameters parameters=engine.getSSLParameters();
			parameters.setEndpointIdentificationAlgorithm(verifyHostname ? "HTTPS" : null);
			engine.setSSLParameters(parameters);
			ssl=new SslHandler(engine);
			ssl.setHandshakeTimeoutMillis(Config.DEFAULT_INTERNAL_TIMEOUT);
		}

		// A per-connect initializer installs codecs and TLS before any traffic arrives.
		Bootstrap b=getClientBootstrap().clone().handler(new ChannelInitializer<SocketChannel>() {
			@Override
			public void initChannel(SocketChannel ch) {
				client.channel=ch;
				if (ssl!=null) ch.pipeline().addLast(ssl);
				ch.pipeline().addLast(
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
							ctx.fireChannelInactive();
						}
					},
					inbound,
					new NettyOutboundHandler()
				);
			}
		});
		ChannelFuture f=b.connect(sa);
		Channel chan=f.channel();
		boolean connected=false;
		try {
			f.await();
			if (!f.isSuccess()) {
				throw new IOException("Failed to connect to peer at "+sa,f.cause());
			}
			if (ssl!=null) {
				var handshake=ssl.handshakeFuture();
				handshake.await();
				if (!handshake.isSuccess()) {
					throw new IOException("TLS handshake failed for "+sa,handshake.cause());
				}
			}
			connected=true;
			return client;
		} finally {
			if (!connected) chan.close().syncUninterruptibly();
		}
	}

	/** Updates the receive limit, for example after successful peer verification. */
	@Override
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
		if (encodedLength(m)<0) return false;
		boolean queued;
		try {
			queued = outbound.offer(m, Config.DEFAULT_INTERNAL_TIMEOUT, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
		return queued && afterQueue(ch, m);
	}

	/**
	 * Non-blocking send. Returns immediately if the outbound queue is full.
	 */
	@Override
	public boolean trySendMessage(Message m) {
		Channel ch = channel;
		if (ch == null || !ch.isActive()) return false;
		if (encodedLength(m)<0) return false;
		return outbound.offer(m) && afterQueue(ch, m);
	}

	/** Schedules a drain for a queued message, or withdraws it if the channel died meanwhile. */
	private boolean afterQueue(Channel ch, Message m) {
		if (!ch.isActive()) {
			outbound.remove(m);
			return false;
		}
		flushPending();
		return true;
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
			Message m=outbound.poll();
			if (m == null) break;
			ch.write(m);
			count++;
		}
		if (count > 0) {
			ch.flush();
		}
	}

	@Override
	public void setOutboundLimits(int messageLimit, long byteLimit) {
		outbound.setLimits(messageLimit, byteLimit);
	}

	private void clearOutbound() {
		outbound.clear();
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
		Channel ch;
		synchronized (this) {
			ch=channel;
			channel=null;
		}
		clearOutbound();
		if (ch==null) return;
		ChannelFuture closeFuture=ch.close();
		if (!ch.eventLoop().inEventLoop()) closeFuture.syncUninterruptibly();
 	}

	@Override
	public long getReceivedCount() {
		return inboundHandler.getReceivedCount();
	}

}
