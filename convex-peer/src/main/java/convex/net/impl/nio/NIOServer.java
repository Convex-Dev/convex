package convex.net.impl.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.StandardSocketOptions;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Constants;
import convex.core.exceptions.BadFormatException;
import convex.core.message.Message;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.util.Utils;
import convex.net.AServer;
import convex.peer.Config;
import convex.peer.Server;

/**
 * NIO Server implementation that handles incoming messages on a given port.
 *
 * Allocates a single thread for the selector.
 *
 * Incoming messages are associated with a Connection (which is created if
 * required), then placed on the receive message queue. This will block if the
 * receive queue is full (thereby applying back-pressure to clients)
 *
 */
public class NIOServer extends AServer {
	public static final int DEFAULT_PORT = 18888;

	private static final Logger log = LoggerFactory.getLogger(NIOServer.class.getName());

	protected static final long SELECT_TIMEOUT = 1000;

	protected static final long PRUNE_TIMEOUT = 60000;

	private ServerSocketChannel ssc = null;

	private Selector selector = null;

	private boolean running = false;

	private final Consumer<Message> receiveAction;

	private final AStore store;
	
	protected NIOServer(AStore store, Consumer<Message> receiveAction) {
		this.store=store;
		this.receiveAction=receiveAction;
	}
	
	
	private AStore getStore() {
		return store;
	}

	/**
	 * Creates a new unlaunched NIO server component
	 * 
	 * @param server Peer Server instance for this NIOServer
	 * @return New NIOServer instance
	 */
	public static NIOServer create(Server server) {
		return new NIOServer(server.getStore(),server.getReceiveAction());
	}

	/**
	 * Launch NIO Server, binding to a given socket address
	 * 
	 * @param bindAddress Address to bind to, or null to bind to all addresses (unspecified)
	 * @param port Port to use. If 0 or null, a default port will be used, with fallback to a random port
	 * @throws IOException in case of IO problem
	 */
	public void launch() throws IOException {
		ssc = ServerSocketChannel.open();

		// Set receive buffer size
		ssc.socket().setReceiveBufferSize(Config.SOCKET_SERVER_BUFFER_SIZE);
		ssc.socket().setReuseAddress(true);

		String bindAddress = "::";
		
		// Bind to a port
		{
			InetSocketAddress bindSA;	
			Integer port=getPort();
			if (port == null) {
				port = 0;
			}
			if (port<=0) {
				try {
					bindSA = new InetSocketAddress(bindAddress, Constants.DEFAULT_PEER_PORT);
					ssc.bind(bindSA);
				} catch (IOException e) {
					// try again with random port
					bindSA = new InetSocketAddress(bindAddress, 0);
					ssc.bind(bindSA);
				}
			} else {
				bindSA = new InetSocketAddress(bindAddress, port);
				ssc.bind(bindSA);
			}
			
			// Find out which port we actually bound to
			bindSA = (InetSocketAddress) ssc.getLocalAddress();
			setPort(ssc.socket().getLocalPort());
		}
		
		// change to bnon-blocking mode
		ssc.configureBlocking(false);

		// Register for accept. Do this before selection loop starts and
		// before we return from launch!
		selector = Selector.open();
		ssc.register(selector, SelectionKey.OP_ACCEPT);

		// set running status now, so that loops don't terminate
		running = true;

		Thread selectorThread = new Thread(selectorLoop, "NIO Server loop on port: " + getPort());
		selectorThread.setDaemon(true); // daemon thread so it doesn't stop shutdown
		selectorThread.start();
		log.debug("NIO server started on port {}", getPort());
	}
	
	long lastConnectionPrune=0;

	/**
	 * Runnable class for accepting socket connections and incoming data, one per
	 * peer If this gets maxed out, rely on backpressure to throttle clients.
	 */
	private Runnable selectorLoop = new Runnable() {
		@Override
		public void run() {
			// Use the store configured for the owning server.
			Stores.setCurrent(getStore());
			try {
				// loop unless we are interrupted
				while (running && !Thread.currentThread().isInterrupted()) {
					
					selector.select(SELECT_TIMEOUT);
					

					Set<SelectionKey> keys = selector.selectedKeys();
					Iterator<SelectionKey> it = keys.iterator();
					while (it.hasNext()) { 
						SelectionKey key = it.next();
						it.remove();

						try {
							if (key.isAcceptable()) {
								accept(selector);
							} 
							if (key.isReadable()) {
								selectRead(key);
							} 
							if (key.isWritable()) {
								selectWrite(key);
							}
						} catch (IOException e) {
							// IO Exception, just lose the key?
							log.debug("IOException, closing channel");
							key.cancel();
						}  catch (CancelledKeyException e) {
							log.debug("Cancelled key: {}", e.getMessage());
							key.cancel();
						} 
					}
					
					long ts=System.currentTimeMillis();
					if (ts>lastConnectionPrune+PRUNE_TIMEOUT) {
						pruneConnections(ts,selector.keys());
						lastConnectionPrune=ts;
					}

					// keys.clear();
				}
				
			} catch (Exception e) {
				log.error("Unexpected Exception, terminating selector loop: ", e);
			} finally {
				try {
					// close all client channels
					for (SelectionKey key : selector.keys()) {
						key.channel().close();
					}
					selector.close();
					selector = null;
				} catch (IOException e) {
					log.error("IOException while closing NIO server",e);
				} finally {
					selector = null;
				}

				if (ssc != null) {
					try {
						ssc.close();
					} catch (IOException e) {
						log.error("IOException while closing NIO socket channel",e);
					} finally {
						ssc = null;
					}
				}

			}
			log.debug("Selector loop ended on port: " + getPort());
		}


	};


	@Override
	public Integer getPort() {
		if (ssc == null)
			return 0;
		ServerSocket socket = ssc.socket();
		if (socket == null)
			return 0;
		return socket.getLocalPort();
	}

	/**
	 * Prune old connections
	 * @param keys Keys to examine
	 */
	protected void pruneConnections(long ts,Set<SelectionKey> keys) {
		int n=keys.size();
		for (SelectionKey key:keys) {
			Connection conn=(Connection) key.attachment();
			if (conn!=null) {
				long age=conn.getLastActivity()-ts;
				
				// prune more aggressively if we have more connections 
				if (age>(1000000L/(n+10))) {
					log.info("Pruning inactive client connection, age = {}",age);
					conn.close();
					key.cancel();
				}
			}
		}
	}

	protected void selectWrite(SelectionKey key) throws IOException {
		// attach a Connection if needed for this client
		ensureConnection(key);

		Connection.selectWrite(key);
	}

	private Connection ensureConnection(SelectionKey key) throws IOException {
		Connection clientConnection = (Connection) key.attachment();
		if (clientConnection != null)
			return clientConnection;
		SocketChannel sc = (SocketChannel) key.channel();
		assert (!sc.isBlocking());
		clientConnection = createClientConnection(sc);
		key.attach(clientConnection);
		return clientConnection;
	}

	private Connection createClientConnection(SocketChannel sc) throws IOException {
		return Connection.create(sc, getReceiveAction(), null);
	}

	protected Consumer<Message> getReceiveAction() {
		return receiveAction;
	}

	protected void selectRead(SelectionKey key) throws IOException {

		// log.info("Connection read from: "+sc.getRemoteAddress()+" with key:"+key);
		Connection conn = ensureConnection(key);
		if (conn == null)
			throw new IOException("No Connection in selection key");
		try {
			int n = conn.handleChannelRecieve();
			if (n < 0) {
				key.cancel();
				log.trace("EOS on channel?");
			} else if (n==0) {
				log.trace("No bytes received for key: {}", key);
			}
		} catch (ClosedChannelException | SocketException e) {
			log.trace("Channel closed ("+Utils.getClassName(e)+") from: {}", conn.getRemoteAddress());
			key.cancel();
		} catch (BadFormatException e) {
			log.info("Cancelled connection: Bad data format from: {} message: {}", conn.getRemoteAddress(),
					e.getMessage());
			// TODO: blacklist peer?
			key.cancel();
		} catch (Exception e) {
			log.warn("Unexpected exception in receive handler", e.getCause());
			key.cancel();
		}
	}

	@Override
	public void finalize() {
		close();
	}

	@Override
	public void close() {
		running = false;
		if (selector != null) {
			selector.wakeup();
		}

	}

	private void accept(Selector selector) throws IOException, ClosedChannelException {
		SocketChannel socketChannel = ssc.accept();
		if (socketChannel == null)
			return; // false alarm? Nobody there?
		log.debug("New connection accepted: {}", socketChannel);
		socketChannel.configureBlocking(false);

		// We don't want Nagle
		// Generally, we want to send packets as fast as possible, they are usually quite small
		// Low latency is the primary concern
		socketChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
		socketChannel.register(selector, SelectionKey.OP_READ);
	}

	/**
	 * Gets the host address for this server (including port), or null if closed
	 * 
	 * @return Host address
	 */
	public InetSocketAddress getHostAddress() {
		if (ssc == null)
			return null;
		ServerSocket socket = ssc.socket();
		if (socket == null)
			return null;
		return new InetSocketAddress(socket.getInetAddress(), socket.getLocalPort());
	}

}
