package convex.net.impl.netty;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Constants;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.message.Message;
import convex.core.store.NullStore;
import convex.core.util.Shutdown;
import convex.core.util.Utils;
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

	static volatile EventLoopGroup bossGroup=null;

	private Channel channel;

	/**
	 * Tracks all active inbound client channels. Auto-removes on close.
	 */
	private final ChannelGroup clientChannels =
		new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

	/**
	 * Maximum number of inbound client connections accepted (#482).
	 * Configurable via Keywords.MAX_CONNECTIONS; defaults to Config.MAX_CLIENT_CONNECTIONS.
	 */
	private volatile int maxClientConnections = Config.MAX_CLIENT_CONNECTIONS;

	/** Maximum encoded inbound message length, enforced before full message allocation. */
	private volatile int maxMessageLength = (int) convex.core.cpos.CPoSConstants.MAX_MESSAGE_LENGTH;

	/** Shared, byte-bounded queue of replies to all inbound connections. */
	private final ServerOutboundQueue outbound = new ServerOutboundQueue(
		Config.SERVER_OUTBOUND_QUEUE_BYTE_LIMIT, Config.OUTBOUND_QUEUE_BYTE_LIMIT);

	/**
	 * Delivery function for inbound messages. Returns null if accepted,
	 * or a blocking retry predicate if the queue was full.
	 */
	private Function<Message, Predicate<Message>> deliver;

	protected synchronized static EventLoopGroup getEventLoopGroup() {
		if (bossGroup!=null) return bossGroup;
		// Boss group only accepts inbound connections — 1 thread is sufficient
		bossGroup=new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
		Shutdown.addHook(Shutdown.SERVER,()->{
			EventLoopGroup bg=bossGroup;
			if (bg!=null) {
				bossGroup=null;
				bg.shutdownGracefully();
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
		Object maxConns=server.getConfig().get(Keywords.MAX_CONNECTIONS);
		if (maxConns!=null) {
			ns.setMaxClientConnections(Utils.toInt(maxConns));
		}
		return ns;
	}

	/**
	 * Sets the maximum number of inbound client connections (#482). New
	 * connections beyond this limit are rejected and closed. Takes effect for
	 * connections accepted after the call; existing connections are unaffected.
	 * @param limit Maximum connections (must be positive)
	 */
	public void setMaxClientConnections(int limit) {
		if (limit<=0) throw new IllegalArgumentException("Connection limit must be positive: "+limit);
		this.maxClientConnections=limit;
	}

	/**
	 * @return Maximum number of inbound client connections currently configured
	 */
	public int getMaxClientConnections() {
		return maxClientConnections;
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
            	 if (clientChannels.size() >= maxClientConnections) {
            		 log.warn("Connection limit reached ({}), rejecting {}",
            			 maxClientConnections, ch.remoteAddress());
            		 ch.close();
            		 return;
            	 }
            	 clientChannels.add(ch);

				 Function<Message, Predicate<Message>> deliverFn =
					 (deliver != null) ? deliver : wrapReceiveAction();
				 NettyInboundHandler inbound=new NettyInboundHandler(deliverFn,null,maxMessageLength);
            	 NettyServerConnection conn=new NettyServerConnection(ch,inbound,outbound);
            	 inbound.setConnection(conn);
            	 inbound.setDisconnectAction(getDisconnectAction()); // #566: eager per-connection cleanup
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
        		f= b.bind("0.0.0.0", port).sync();
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
        		f= b.bind("0.0.0.0", port).sync();
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
		// Stop accepting first, then close established clients. Waiting for both
		// operations makes close a real lifecycle boundary, which NodeServer relies on
		// when rolling back a launch that failed after binding successfully. A handler
		// is still allowed to initiate close from its own event loop; that case must not
		// wait on itself and completion remains asynchronous.
		Channel serverChannel = channel;
		boolean eventLoopThread = serverChannel != null && serverChannel.eventLoop().inEventLoop();
		if (!eventLoopThread) {
			for (Channel client : clientChannels) {
				if (client.eventLoop().inEventLoop()) {
					eventLoopThread = true;
					break;
				}
			}
		}
		channel = null;
		ChannelFuture serverClose = (serverChannel != null) ? serverChannel.close() : null;
		var clientsClose = clientChannels.close();
		if (!eventLoopThread) {
			if (serverClose != null) serverClose.syncUninterruptibly();
			clientsClose.awaitUninterruptibly();
		}
		outbound.close();
	}

	public void waitForClose() throws InterruptedException {
		channel.closeFuture().sync();
	}

	@Override
	public int getClientConnectionCount() {
		return clientChannels.size();
	}

	@Override
	public InetSocketAddress getHostAddress() {
		return (InetSocketAddress) channel.localAddress();
	}


	public void setReceiveAction(Consumer<Message> handler) {
		receiveAction=handler;
	}

	/**
	 * Sets the non-blocking message delivery function used by the Netty inbound handler.
	 * A returned predicate is retried on a virtual thread while reads on that channel pause.
	 *
	 * @param handler delivery function implementing the backpressure contract
	 */
	public void setMessageDelivery(Function<Message, Predicate<Message>> handler) {
		this.deliver=handler;
	}

	/**
	 * Sets the maximum encoded inbound message length.
	 *
	 * @param limit maximum bytes, must be positive
	 */
	public void setMaxMessageLength(int limit) {
		if (limit <= 0) throw new IllegalArgumentException("Message length limit must be positive: " + limit);
		this.maxMessageLength=limit;
	}

	public int getMaxMessageLength() {
		return maxMessageLength;
	}

}
