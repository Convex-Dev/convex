package convex.net.impl.netty;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Constants;
import convex.core.data.ACell;
import convex.core.message.Message;
import convex.core.store.NullStore;
import convex.core.util.Shutdown;
import convex.net.AServer;
import convex.peer.Config;
import convex.peer.Server;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.GlobalEventExecutor;

public class NettyServer extends AServer {

	static final Logger log = LoggerFactory.getLogger(NettyServer.class);

	static EventLoopGroup bossGroup=null;

	private Channel channel;

	/**
	 * Tracks all active inbound client channels. Auto-removes on close.
	 */
	private final ChannelGroup clientChannels =
		new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

	/**
	 * Delivery function for inbound messages. Returns null if accepted,
	 * or a blocking retry predicate if the queue was full.
	 */
	private Function<Message, Predicate<Message>> deliver;

	protected synchronized static EventLoopGroup getEventLoopGroup() {
		if (bossGroup!=null) return bossGroup;
		bossGroup=new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
		Shutdown.addHook(Shutdown.SERVER,()->{
			if (bossGroup!=null) {
				bossGroup.shutdownGracefully();
			}
		});
		return bossGroup;
	}

	// Receive action. Default is just an echo. Users should set a receive action
	private Consumer<Message> receiveAction=m->{
		try {
			ACell payload=m.getPayload(NullStore.INSTANCE);
			m.returnMessage(Message.createResult(m.getRequestID(), payload, null));
		} catch (Exception e) {
			System.err.println(e);
		}
	};

	public NettyServer(Integer port) {
		setPort(port);
	}


	public static NettyServer create(Server server) {
		NettyServer ns=new NettyServer(null);
		ns.receiveAction=server.getReceiveAction();
		ns.deliver=server::deliverMessage;
		return ns;
	}


	public void launch() throws IOException,InterruptedException {
        EventLoopGroup bossGroup = NettyServer.getEventLoopGroup();
        EventLoopGroup workerGroup = NettyConnection.getEventLoopGroup();
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
         .channel(NioServerSocketChannel.class)
         .childHandler(new ChannelInitializer<SocketChannel>() {
             @Override
             public void initChannel(SocketChannel ch) throws Exception {
            	 // Enforce connection limit
            	 if (clientChannels.size() >= Config.MAX_CLIENT_CONNECTIONS) {
            		 log.warn("Connection limit reached ({}), rejecting {}",
            			 Config.MAX_CLIENT_CONNECTIONS, ch.remoteAddress());
            		 ch.close();
            		 return;
            	 }
            	 clientChannels.add(ch);

            	 Predicate<Message> returnHandler=m->{
            		 ch.writeAndFlush(m);
            		 return true;
            	 };
            	 Function<Message, Predicate<Message>> deliverFn =
            		 (deliver != null) ? deliver : wrapReceiveAction();
            	 NettyInboundHandler inbound=new NettyInboundHandler(deliverFn,returnHandler);
                 ch.pipeline().addLast(inbound,new NettyOutboundHandler());
             }
         })
         .option(ChannelOption.SO_BACKLOG, 128) // Backlog of incoming connection requests
         .childOption(ChannelOption.SO_KEEPALIVE, true);

        Integer port=getPort();
        ChannelFuture f=null;
        if (port==null) try {
          	port=Constants.DEFAULT_PEER_PORT; // use default port in first instance
        	InetSocketAddress bindAddress=new InetSocketAddress("::",port);
        	try {
        		f = b.bind(bindAddress).sync();
         	} catch (java.nio.channels.UnsupportedAddressTypeException e) {
        		f= b.bind("0.0.0.0", port);
        		log.warn("Unable to bind IPv6 address, falling back to IPv4");
        	}
        } catch (Exception e) {
        	// failed so try with random port
        	log.debug("Default peer port not available, trying random port");
        	port=0;
    	}

        if (f==null) {
        	InetSocketAddress bindAddress=new InetSocketAddress("::",port);
        	try {
        		f = b.bind(bindAddress).sync();
        	} catch (java.nio.channels.UnsupportedAddressTypeException e) {
        		f= b.bind("0.0.0.0", port);
        		log.warn("Unable to bind IPv6 address, falling back to IPv4");
        	}
        }
    	// Check local port
        InetSocketAddress localAddress=(InetSocketAddress) f.channel().localAddress();
    	setPort(localAddress.getPort());
  		log.debug("Netty Server started on port: "+getPort());

   		this.channel=f.channel();
    }

	/**
	 * Wraps the legacy receiveAction Consumer as a Function for backward
	 * compatibility (e.g. standalone NettyServer without a Server instance).
	 */
	private Function<Message, Predicate<Message>> wrapReceiveAction() {
		Consumer<Message> action = receiveAction;
		return m -> {
			action.accept(m);
			return null; // Consumer path handles everything internally
		};
	}

	@Override
	public Consumer<Message> getReceiveAction() {
		return receiveAction;
	}

	public static void main(String... args) throws Exception {
		try (NettyServer server=new NettyServer(8000)) {
			server.launch();

			server.waitForClose();
		}
	}

	@Override
	public void close() {
		clientChannels.close();
		if (channel!=null) {
			channel.close();
		}
	}

	public void waitForClose() throws InterruptedException {
		channel.closeFuture().sync();
	}

	@Override
	public InetSocketAddress getHostAddress() {
		return (InetSocketAddress) channel.localAddress();
	}


	public void setReceiveAction(Consumer<Message> handler) {
		receiveAction=handler;
	}

}
