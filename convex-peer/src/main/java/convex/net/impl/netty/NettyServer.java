package convex.net.impl.netty;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Constants;
import convex.core.crypto.TLS;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.message.AConnection;
import convex.core.message.Message;
import convex.core.store.NullStore;
import convex.core.util.Shutdown;
import convex.core.util.Utils;
import convex.net.AServer;
import convex.net.IncomingHandler;
import convex.peer.Config;
import convex.peer.Server;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.EventLoop;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.GlobalEventExecutor;

public class NettyServer extends AServer {

	static final Logger log = LoggerFactory.getLogger(NettyServer.class);

	static volatile EventLoopGroup bossGroup=null;

	private Channel channel;
	private SSLContext sslContext;

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
		Config.SERVER_OUTBOUND_QUEUE_BYTE_LIMIT, Config.SERVER_CONNECTION_PENDING_BYTE_LIMIT);

	/**
	 * Delivery function for inbound messages. Returns null if accepted,
	 * or a blocking retry predicate if the queue was full.
	 */
	private Function<Message, Predicate<Message>> deliver;
	private volatile Function<AConnection,IncomingHandler> connectionHandler;
	/** Close futures on channels can finish before their disconnect callbacks. */
	private final Set<AcceptedConnection> incomingHandlers=ConcurrentHashMap.newKeySet();

	protected synchronized static EventLoopGroup getEventLoopGroup() {
		if (bossGroup!=null) return bossGroup;
		// Boss group only accepts inbound connections — 1 thread is sufficient.
		// Daemon, like the worker group: the shutdown hook that stops it only runs
		// once the JVM begins to exit, which a live non-daemon thread prevents.
		DefaultThreadFactory tf = new DefaultThreadFactory("convex-netty-boss", true);
		bossGroup=new MultiThreadIoEventLoopGroup(1, tf, NioIoHandler.newFactory());
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

	/** Enables TLS on this listener. Configure before launch; null retains TCP. */
	public void setSSLContext(SSLContext context) {
		if (channel!=null) throw new IllegalStateException("Listener already launched");
		sslContext=context;
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
				 if (sslContext!=null) {
					 SSLEngine engine=TLS.serverEngine(sslContext);
					 SslHandler ssl=new SslHandler(engine);
					 ssl.setHandshakeTimeoutMillis(Config.DEFAULT_INTERNAL_TIMEOUT);
					 ch.pipeline().addLast(ssl);
				 }

				 Function<AConnection,IncomingHandler> factory=connectionHandler;
				 AcceptedConnection accepted=(factory==null) ? null : new AcceptedConnection(getDisconnectAction(),incomingHandlers,ch.eventLoop());
				 if (accepted!=null) incomingHandlers.add(accepted);
				 Function<Message, Predicate<Message>> deliverFn = (accepted!=null) ? accepted :
					 ((deliver != null) ? deliver : wrapReceiveAction());
				 NettyInboundHandler inbound=new NettyInboundHandler(deliverFn,null,maxMessageLength);
            	 NettyServerConnection conn=new NettyServerConnection(ch,inbound,outbound);
            	 inbound.setConnection(conn);
				 inbound.setDisconnectAction((accepted==null) ? getDisconnectAction() : accepted);
                 ch.pipeline().addLast(inbound,new NettyOutboundHandler());
				 if (accepted!=null) {
					 accepted.connection=conn;
					 try {
						 accepted.open(factory.apply(conn));
					 } catch (Throwable e) {
						 if (e instanceof VirtualMachineError && !(e instanceof StackOverflowError)) throw e;
						 log.warn("Incoming connection handler failed",e);
						 if (!accepted.opened) accepted.open(null);
						 conn.close();
					 }
				 }
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
		if (!eventLoopThread) {
			// The socket may already have left clientChannels when its cleanup calls close.
			for (AcceptedConnection accepted:incomingHandlers) {
				if (accepted.eventLoop.inEventLoop()) { eventLoopThread=true; break; }
			}
		}
		channel = null;
		ChannelFuture serverClose = (serverChannel != null) ? serverChannel.close() : null;
		var clientsClose = clientChannels.close();
		if (!eventLoopThread) {
			if (serverClose != null) serverClose.syncUninterruptibly();
			clientsClose.awaitUninterruptibly();
			for (AcceptedConnection accepted:incomingHandlers) accepted.finished.join();
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


	/**
	 * Sets the action for each received message. It runs on a network event loop that
	 * serves many connections, so it must not block: hand off any work that may wait.
	 *
	 * @param handler action for each received message
	 */
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
	 * Chooses a handler once per accepted connection, before its first message.
	 * The factory must not block; null results refuse the connection. A null factory
	 * restores the message-delivery/receive-action configuration. Changes affect new
	 * connections only. Matching replies bypass the selected handler.
	 *
	 * @param factory connection handler factory, or null for legacy delivery
	 */
	public void setConnectionHandler(Function<AConnection,IncomingHandler> factory) {
		connectionHandler=factory;
	}

	/** One object per connection; dispatch allocates nothing and looks up no handler map. */
	static final class AcceptedConnection implements Function<Message,Predicate<Message>>,Consumer<AConnection> {
		private final Consumer<AConnection> disconnected;
		private final Set<AcceptedConnection> owners;
		private final EventLoop eventLoop;
		private final CompletableFuture<Void> finished=new CompletableFuture<>();
		AConnection connection;
		IncomingHandler handler;
		private boolean closed;
		private boolean opened;

		AcceptedConnection(Consumer<AConnection> disconnected) {
			this(disconnected,null,null);
		}

		AcceptedConnection(Consumer<AConnection> disconnected,Set<AcceptedConnection> owners,EventLoop eventLoop) {
			this.disconnected=disconnected;
			this.owners=owners;
			this.eventLoop=eventLoop;
		}

		void open(IncomingHandler incoming) {
			// A factory may close its socket synchronously before returning a handler.
			handler=incoming;
			opened=true;
			if (closed) {
				finish();
				return;
			}
			if (incoming==null) connection.close();
		}

		@Override public Predicate<Message> apply(Message message) {
			if (closed || handler==null) return null;
			try {
				if (connection.completeRequest(message)) return null;
			} catch (convex.core.exceptions.BadFormatException e) {
				throw new IllegalArgumentException("Malformed response",e);
			}
			return handler.handle(message);
		}

		@Override public void accept(AConnection connection) {
			if (closed) return;
			closed=true;
			if (opened) finish();
		}

		private void finish() {
			try {
				if (handler!=null) handler.close();
			} finally {
				handler=null;
				try {
					if (disconnected!=null) disconnected.accept(connection);
				} finally {
					if (owners!=null) owners.remove(this);
					finished.complete(null);
				}
			}
		}
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
