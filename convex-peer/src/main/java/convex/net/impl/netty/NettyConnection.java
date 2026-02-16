package convex.net.impl.netty;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.Vectors;
import convex.core.message.Message;
import convex.core.message.MessageType;
import convex.core.util.Shutdown;
import convex.net.AConnection;
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

public class NettyConnection extends AConnection {

	static final Logger log = LoggerFactory.getLogger(NettyConnection.class.getName());

	/**
	 * Static client connection worker
	 */
	static EventLoopGroup workerGroup = null;

	static Bootstrap clientBootstrap = null;

	private Channel channel;

	private NettyInboundHandler inboundHandler;

	/**
	 * Lock used for client-side write backpressure. Application threads wait on
	 * this when the channel is not writable; the writability change handler
	 * notifies when the channel becomes writable again.
	 */
	final Object writabilityLock = new Object();

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
			workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());

			Shutdown.addHook(Shutdown.CONNECTION, () -> {
				if (workerGroup != null) {
					workerGroup.shutdownGracefully();
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
			b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) Config.DEFAULT_CLIENT_TIMEOUT);
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
		NettyInboundHandler inbound=new NettyInboundHandler(deliverFn,null);

		NettyConnection client = new NettyConnection(chan,inbound);

		// Add writability change handler to wake application threads on backpressure clear
		f.channel().pipeline().addLast(
			new ChannelInboundHandlerAdapter() {
				@Override
				public void channelWritabilityChanged(ChannelHandlerContext ctx) {
					if (ctx.channel().isWritable()) {
						synchronized (client.writabilityLock) {
							client.writabilityLock.notifyAll();
						}
					}
					ctx.fireChannelWritabilityChanged();
				}
			},
			inbound,
			new NettyOutboundHandler()
		);

		return client;
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
	public boolean sendMessage(Message m)  {
		if (!channel.isActive()) return false;

		// Wait if channel is not writable (TCP backpressure from server).
		// Blocks the APPLICATION thread, not the Netty event loop.
		if (!channel.isWritable()) {
			try {
				synchronized (writabilityLock) {
					while (!channel.isWritable() && channel.isActive()) {
						writabilityLock.wait(100); // periodic check
					}
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
			if (!channel.isActive()) return false;
		}

		channel.writeAndFlush(m);
		return true;
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
