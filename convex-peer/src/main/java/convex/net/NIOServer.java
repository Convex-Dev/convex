package convex.net;

import java.io.Closeable;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Constants;
import convex.core.exceptions.BadFormatException;
import convex.core.store.Stores;
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
public class NIOServer implements Closeable {
	public static final int DEFAULT_PORT = 18888;

	private static final Logger log = LoggerFactory.getLogger(NIOServer.class.getName());

	private ServerSocketChannel ssc = null;


	private Selector selector = null;

	private boolean running = false;

	private final Server server;

	private NIOServer(Server server) {
		this.server = server;
	}

	/**
	 * Creates a new unlaunched NIO server
	 * 
	 * @param server       Peer Server instance for this NIOServer
	 * @param receiveQueue Queue for received messages
	 * @return New NIOServer instance
	 */
	public static NIOServer create(Server server) {
		return new NIOServer(server);
	}

	public void launch(Integer port) {
		launch(null, port);
	}

	public void launch(String bindAddress, Integer port) {
		if (port == null)
			port = 0;

		try {
			ssc = ServerSocketChannel.open();

			// Set receive buffer size
			ssc.socket().setReceiveBufferSize(Constants.SOCKET_SERVER_BUFFER_SIZE);

			bindAddress = (bindAddress == null) ? "localhost" : bindAddress;
			InetSocketAddress address = new InetSocketAddress(bindAddress, port);
			ssc.bind(address);
			address = (InetSocketAddress) ssc.getLocalAddress();
			ssc.configureBlocking(false);
			port = ssc.socket().getLocalPort();

			// Register for accept. Do this before selection loop starts and
			// before we return from launch!
			selector = Selector.open();
			ssc.register(selector, SelectionKey.OP_ACCEPT);

			// set running status now, so that loops don't terminate
			running = true;

			Thread selectorThread = new Thread(selectorLoop, "NIO Server selector loop on port: " + port);
			selectorThread.setDaemon(true);
			selectorThread.start();
			log.debug("NIO server started on port {}", port);
		} catch (Exception e) {
			throw new Error("Can't bind NIOServer to port: " + port, e);
		}
	}

	/**
	 * Runnable class for accepting socket connections and incoming data, one per
	 * peer If this gets maxed out, rely on backpressure to throttle clients.
	 */
	private Runnable selectorLoop = new Runnable() {
		@Override
		public void run() {
			// Use the store configured for the owning server.
			Stores.setCurrent(server.getStore());
			try {

				while (running) {
					selector.select(1000);

					Set<SelectionKey> keys = selector.selectedKeys();
					Iterator<SelectionKey> it = keys.iterator();
					while (it.hasNext()) {
						SelectionKey key = it.next();
						it.remove();

						try {
							// Just do one op on each key
							if (key.isAcceptable()) {
								accept(selector);
							} else if (key.isReadable()) {
								selectRead(key);
							} else if (key.isWritable()) {
								selectWrite(key);
							}
						} catch (ClosedChannelException e) {
							// channel was closed, just lose the key?
							log.debug("Client closed channel");
							key.cancel();
						} catch (IOException e) {
							log.warn("Unexpected IOException, canceling key: {}", e);
							// e.printStackTrace();
							key.cancel();
						} catch (CancelledKeyException e) {
							log.debug("Cancelled key: {}", e);
							// e.printStackTrace();
							key.cancel();
						}
					}
					// keys.clear();
				}
			} catch (IOException e) {
				log.error("Unexpected IOException, terminating selector loop: {}", e);
				// print error and terminate
				e.printStackTrace();
			} finally {
				try {
					// close all client channels
					for (SelectionKey key : selector.keys()) {
						key.channel().close();
					}
					selector.close();
					selector = null;
				} catch (IOException e) {
					log.error("IOException while closing NIO server");
					e.printStackTrace();
				} finally {
					selector = null;
				}

				if (ssc != null) {
					try {
						ssc.close();
					} catch (IOException e) {
						log.error("IOException while closing NIO socket channel");
						e.printStackTrace();
					} finally {
						ssc = null;
					}
				}

				log.debug("Selector loop ended on port: " + getPort());
			}
		}
	};

	/**
	 * Gets the port that this server instance is listening on.
	 * 
	 * @return Port number, or 0 if a server socket is not bound.
	 */
	public int getPort() {
		if (ssc == null)
			return 0;
		ServerSocket socket = ssc.socket();
		if (socket == null)
			return 0;
		return socket.getLocalPort();
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
		return Connection.create(sc, server.getReceiveAction(), server.getStore(), null);
	}

	protected void selectRead(SelectionKey key) throws IOException {

		// log.info("Connection read from: "+sc.getRemoteAddress()+" with key:"+key);
		Connection conn = ensureConnection(key);
		if (conn == null)
			throw new Error("No PeerConnection specified");
		try {
			int n = conn.handleChannelRecieve();
			if (n == 0) {
				log.debug("No bytes received for key: {}", key);
			}
		} catch (ClosedChannelException | SocketException e) {
			log.debug("Channel closed from: {}", conn.getRemoteAddress());
			key.cancel();
		} catch (BadFormatException e) {
			log.warn("Cancelled connection: Bad data format from: {} message: {}", conn.getRemoteAddress(),
					e.getMessage());
			// TODO: blacklist peer?
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

		// TODO: Confirm we don't want Nagle?
		// Generally, we want to send packets as fast as possible.
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
