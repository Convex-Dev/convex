package convex.net.impl.netty;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.function.Consumer;

import convex.core.data.Vectors;
import convex.core.message.Message;
import convex.core.message.MessageType;
import convex.core.util.Shutdown;
import convex.net.AConnection;
import convex.peer.Config;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

public class NettyConnection extends AConnection {

	/**
	 * Static client connection worker
	 */
	static EventLoopGroup workerGroup = null;

	static Bootstrap clientBootstrap = null;

	private Channel channel;

	private NettyInboundHandler inboundHandler;

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
			workerGroup = new NioEventLoopGroup();

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

	public static NettyConnection connect(SocketAddress sa, Consumer<Message> receiveAction) throws InterruptedException {
		Bootstrap b = getClientBootstrap();
		ChannelFuture f = b.connect(sa).sync(); // (5)

		Channel chan = f.channel();
		NettyInboundHandler inbound=new NettyInboundHandler(receiveAction,null);
		f.channel().pipeline().addLast(inbound,new NettyOutboundHandler());

		NettyConnection client = new NettyConnection(chan,inbound);
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
		
		// Note: never call await here as might block the IO thread
		@SuppressWarnings("unused")
		ChannelFuture cf=channel.writeAndFlush(m);
		// cf.syncUninterruptibly();
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
