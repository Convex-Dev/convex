package convex.net;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.function.Consumer;

import convex.core.data.Vectors;
import convex.core.util.Shutdown;
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

public class NettyClient {

	/**
	 * Static client connection worker
	 */
	static EventLoopGroup workerGroup = null;

	static Bootstrap clientBootstrap = null;

	private Channel channel;

	private NettyClient(Channel channel) {
		this.channel = channel;
	}

	protected static EventLoopGroup getEventLoopGroup() {
		if (workerGroup != null)
			return workerGroup;

		synchronized (NettyClient.class) {
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

		synchronized (NettyClient.class) {
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

	protected static NettyClient connect(SocketAddress sa, Consumer<Message> receiveAction) throws InterruptedException {
		Bootstrap b = getClientBootstrap();
		ChannelFuture f = b.connect(sa).sync(); // (5)

		Channel chan = f.channel();
		f.channel().pipeline().addLast(new NettyOutboundHandler(),new NettyInboundHandler(receiveAction));

		NettyClient client = new NettyClient(chan);
		return client;
	}

	protected ChannelFuture send(Message m) {
		return channel.writeAndFlush(m);
	}

	public static void main(String... args) throws Exception {
		NettyClient client = connect(new InetSocketAddress("localhost", 8000),null);

		client.send(Message.create(MessageType.QUERY,Vectors.of(1,2,3))).sync();
	}

}
