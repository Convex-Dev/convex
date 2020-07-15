package convex.net;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
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

	private ServerSocketChannel ssc=null;
	
	private BlockingQueue<Message> receiveQueue;
	
	private boolean running=false;
	
	private final Server server;
	
	private NIOServer(Server server, BlockingQueue<Message> receiveQueue) {
		this.server=server;
		this.receiveQueue=receiveQueue;
	}
	
	public static NIOServer create(Server server, BlockingQueue<Message> receiveQueue) {
		return new NIOServer(server,receiveQueue);
	}
	
	public void launch() {
		Integer port=server.getPort();
		
		try {
			ssc=ServerSocketChannel.open();
			InetSocketAddress address=new InetSocketAddress(port);
			ssc.bind(address);
			address=(InetSocketAddress) ssc.getLocalAddress();
			ssc.configureBlocking(false);
			port=ssc.socket().getLocalPort();
			
			// set running status now, so that loops don't terminate
			running=true;
			
			new Thread(selectorLoop,"NIO Server selector loop on port: "+port).start();
			log.info("NIO server started on port "+port);
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
				Selector selector = Selector.open();
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
							log.info("CLient closed channel");
							key.cancel();
						} catch (IOException e) {
							log.info("Unexpected IOException, canceling key: "+e.getMessage());
							// e.printStackTrace();
							key.cancel();
						}
					}
					// keys.clear();
				}
			}

			catch (IOException e) {
				log.info("Unexpected IOException, terminating selector loop: "+e.getMessage());
				// print error and terminate
				e.printStackTrace();
			} finally {
				log.info("Selector loop ended on port: "+getPort());
			}
		}
	};
	
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
				queue.put(m);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		},store);
	}

	protected void selectRead(SelectionKey key) throws IOException {

		// Log.info("Connection read from: "+sc.getRemoteAddress()+" with key:"+key);
		Connection conn=ensurePeerConnection(key);
		if (conn==null) throw new Error("No PeerConnection specified");
		try {
			int n=conn.handleChannelRecieve();
			if (n==0) {
				log.finer("No bytes received for key: "+key);
			}
		} 
		catch (ClosedChannelException e) {
			log.finer("Channel closed from: "+conn.getRemoteAddress());
			key.cancel();
		} 
		catch (BadFormatException e) {
			log.finer("Cancelled connection: Bad data format from: "+conn.getRemoteAddress()+" message: "+e.getMessage());
			// TODO: blacklist peer?
			key.cancel();
			// e.printStackTrace();
		}
	}

	@Override public void finalize() {
		close();
	}

	@Override
	public void close() {
		running=false;
		ServerSocketChannel ssc=this.ssc;
		if (ssc!=null) {
			SocketAddress sa=null;
			try {
				sa = ssc.getLocalAddress();
				ssc.close();
				log.info("Unbinding server socket: "+sa);
			}
			catch (IOException e) {
				log.severe("Unexpected exception when unbinding "+sa+" : ");
				e.printStackTrace();
			}
			this.ssc=null;
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
