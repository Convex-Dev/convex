package convex.net;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import convex.core.exceptions.BadFormatException;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.peer.Server;

/**
 * NIO Server implementation that handles incoming messages on a given port.
 *
 * Allocates a single thread for the selector.
 *
 * Incoming messages are associated with a Connection (which is created if required), then placed
 * on the receive message queue. This will block if the receive queue is full (thereby applying
 * back-pressure to clients)
 *
 */
public class NIOServer implements Closeable {
	public static final int DEFAULT_PORT = 18888;

	private static final Logger log=Logger.getLogger(NIOServer.class.getName());

	static final Level LEVEL_BAD_CONNECTION=Level.WARNING;
	static final Level LEVEL_SERVER=Level.FINER;

	private ServerSocketChannel ssc=null;

	private BlockingQueue<Message> receiveQueue;

	private Selector selector=null;

	private boolean running=false;

	private final Server server;

	private NIOServer(Server server, BlockingQueue<Message> receiveQueue) {
		this.server=server;
		this.receiveQueue=receiveQueue;
	}

	public static NIOServer create(Server server, BlockingQueue<Message> receiveQueue) {
		return new NIOServer(server,receiveQueue);
	}

	public void launch(Integer port) {
		if (port==null) port=0;

		try {
			ssc=ServerSocketChannel.open();
			InetSocketAddress address=new InetSocketAddress(port);
			ssc.bind(address);
			address=(InetSocketAddress) ssc.getLocalAddress();
			ssc.configureBlocking(false);
			port=ssc.socket().getLocalPort();

			// set running status now, so that loops don't terminate
			running=true;

			Thread selectorThread=new Thread(selectorLoop,"NIO Server selector loop on port: "+port);
			selectorThread.setDaemon(true);
			selectorThread.start();
			log.log(LEVEL_SERVER, "NIO server started on port "+port);
		} catch (Exception e) {
			throw new Error("Can't bind NIOServer to port: "+port,e);
		}
	}


	/**
	 * Runnable class for accepting socket connections and incoming data, one per peer
	 * If this gets maxed out, rely on backpressure to throttle clients.
	 */
	private Runnable selectorLoop= new Runnable() {
		@Override
		public void run() {
			// Use the store configured for the owning server.
			Stores.setCurrent(server.getStore());
			try {
				selector = Selector.open();

				ssc.register(selector, SelectionKey.OP_ACCEPT);

				while (running) {
					selector.select(1000);

					Set<SelectionKey> keys = selector.selectedKeys();
					Iterator<SelectionKey> it = keys.iterator();
					while(it.hasNext()) {
						SelectionKey key=it.next();
						it.remove();

						try {
			                if (key.isAcceptable()) {
			                	accept(selector);
			                } else if (key.isReadable()) {
			                	selectRead(key);
			                } else if (key.isWritable()) {
			                	selectWrite(key);
			                }
						} catch (ClosedChannelException e) {
							// channel was closed, just lose the key?
							log.log(LEVEL_BAD_CONNECTION,"Client closed channel");
							key.cancel();
						} catch (IOException e) {
							log.log(LEVEL_BAD_CONNECTION,"Unexpected IOException, canceling key: "+e.getMessage());
							// e.printStackTrace();
							key.cancel();
						}
					}
					// keys.clear();
				}
			} catch (IOException e) {
				log.log(LEVEL_BAD_CONNECTION,"Unexpected IOException, terminating selector loop: "+e.getMessage());
				// print error and terminate
				e.printStackTrace();
			} finally {
				try {
					// close all client channels
					for (SelectionKey key: selector.keys()) {
						key.channel().close();
					}
					selector.close();
					selector=null;
				} catch (IOException e) {
					log.severe("IOException while closing NIO server");
					e.printStackTrace();
				} finally {
					selector=null;
				}


				if (ssc!=null) {
					try {
						ssc.close();
					} catch (IOException e) {
						log.severe("IOException while closing NIO socket channel");
						e.printStackTrace();
					} finally {
						ssc=null;
					}
				}

				log.log(LEVEL_SERVER,"Selector loop ended on port: "+getPort());
			}
		}
	};

	/**
	 * Gets the port that this server instance is listening on.
	 * @return Port number, or 0 if a server socket is not bound.
	 */
	public int getPort() {
		if (ssc==null) return 0;
		ServerSocket socket = ssc.socket();
		if (socket==null) return 0;
		return socket.getLocalPort();
	}

	protected void selectWrite(SelectionKey key) throws IOException {
		SocketChannel sc=(SocketChannel) key.channel();

		// attach a PeerConnection if needed for this client
		if (key.attachment()==null) {
        	key.attach(createPC(sc,receiveQueue,server.getStore()));
    	}

		Connection.selectWrite(key);
	}

	private Connection ensurePeerConnection(SelectionKey key) throws IOException {
		Connection pc=(Connection) key.attachment();
		if (pc!=null) return pc;
		SocketChannel sc=(SocketChannel) key.channel();
		assert(!sc.isBlocking());
		pc=createPC(sc,receiveQueue,server.getStore());
    	key.attach(pc);
    	return pc;
	}

	private static Connection createPC(SocketChannel sc, BlockingQueue<Message> queue, AStore store) throws IOException {
		return Connection.create(sc,m->{
			try {
				// Add message to the received message queue
				queue.put(m);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				log.warning("Interrupted while attempting to add to receive queue");
				Thread.currentThread().interrupt();
			}
		},store,null);
	}

	protected void selectRead(SelectionKey key) throws IOException {

		// log.info("Connection read from: "+sc.getRemoteAddress()+" with key:"+key);
		Connection conn=ensurePeerConnection(key);
		if (conn==null) throw new Error("No PeerConnection specified");
		try {
			int n=conn.handleChannelRecieve();
			if (n==0) {
				log.finer("No bytes received for key: "+key);
			}
		}
		catch (ClosedChannelException e) {
			log.log(LEVEL_SERVER,"Channel closed from: "+conn.getRemoteAddress());
			key.cancel();
		}
		catch (BadFormatException e) {
			log.log(LEVEL_BAD_CONNECTION,"Cancelled connection: Bad data format from: "+conn.getRemoteAddress()+" message: " +e.getMessage());
			// TODO: blacklist peer?
			key.cancel();
		}
	}

	@Override public void finalize() {
		close();
	}

	@Override
	public void close() {
		running=false;
		if (selector!=null) {
			selector.wakeup();
		}

	}

	private void accept(Selector selector) throws IOException, ClosedChannelException {
		SocketChannel socketChannel=ssc.accept();
		if (socketChannel==null) return; // false alarm? Nobody there?
		log.finer("New connection accepted: " + socketChannel);
		socketChannel.configureBlocking(false);
		// TODO: Do we want Nagle?
		// socketChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
		socketChannel.register(selector, SelectionKey.OP_READ);
	}

	/**
	 * Gets the host address for this server (including port), or null if closed
	 * @return Host address
	 */
	public InetSocketAddress getHostAddress() {
		int port=getPort();
		if (port<=0) return null;
		InetSocketAddress sa= new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
		return sa;
	}

}
