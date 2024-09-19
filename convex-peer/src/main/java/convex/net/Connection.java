package convex.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Constants;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.transactions.ATransaction;
import convex.core.util.Counters;
import convex.core.util.Utils;
import convex.core.util.Shutdown;
import convex.net.impl.HandlerException;
import convex.peer.Config;

/**
 * <p>
 * Class representing the low-level NIO network Connection between network participants.
 * </p>
 *
 * <p>
 * Sent messages are sent asynchronously via the shared client selector.
 * </p>
 *
 * <p>
 * Received messages are read by the shared client selector, converted into
 * Message instances, and passed to a Consumer for handling.
 * </p>
 *
 * <p>
 * A Connection "owns" the ByteChannel associated with this Peer connection
 * </p>
 */
@SuppressWarnings("unused")
public class Connection {

	final ByteChannel channel;

	/**
	 * Counter for IDs of all messages sent from this Connection
	 */
	private long idCounter = 0;
	
	/**
	 * Timestamp of last connection activity
	 */
	private long lastActivity;

	/**
	 * Store to use for this connection. Required for responding to incoming
	 * messages.
	 */
	private final AStore store;

	/**
	 * If trusted, the Account Key of the remote peer.
	 */
	private AccountKey trustedPeerKey;

	private static final Logger log = LoggerFactory.getLogger(Connection.class.getName());

	private final MessageReceiver receiver;
	private final MessageSender sender;

	private Connection(ByteChannel channel, Consumer<Message> receiveAction, AStore store,
			AccountKey trustedPeerKey) {
		this.channel = channel;
		receiver = new MessageReceiver(receiveAction, this);
		sender = new MessageSender(channel);
		this.store = store;
		this.lastActivity=Utils.getCurrentTimestamp();
		this.trustedPeerKey = trustedPeerKey;
	}

	/**
	 * Create a PeerConnection using an existing channel. Does not perform any
	 * connection initialisation: channel should already be connected.
	 *
	 * @param channel Byte channel to wrap
	 * @param receiveAction  Consumer to be called when a Message is received
	 * @param store          Store to use when receiving messages.
	 * @param trustedPeerKey Trusted peer account key if this is a trusted
	 *                       connection, if not then null*
	 * @return New Connection instance
	 * @throws IOException If IO error occurs
	 */
	public static Connection create(ByteChannel channel, Consumer<Message> receiveAction, AStore store,
			AccountKey trustedPeerKey) throws IOException {
		// Needed in case server has incoming connections but no outbound?
		ensureSelectorLoop(); 
	
		return new Connection(channel, receiveAction, store, trustedPeerKey);
	}

	/**
	 * Create a Connection by connecting to a remote address
	 *
	 * @param socketAddress   Address to connect to
	 * @param receiveAction A callback Consumer to be called for any received
	 *                      messages on this connection
	 * @param store         Store to use for this Connection
	 * @return New Connection instance
	 * @throws IOException      If connection fails because of any IO problem
	 * @throws TimeoutException If connection cannot be established within an
	 *                          acceptable time (~5s)
	 */
	public static Connection connect(InetSocketAddress socketAddress, Consumer<Message> receiveAction, AStore store)
			throws IOException, TimeoutException {
		return connect(socketAddress, receiveAction, store, null);
	}
	
	/**
	 * Create a Connection by connecting to a remote address
	 *
	 * @param socketAddress    Address to connect to
	 * @param receiveAction  A callback Consumer to be called for any received
	 *                       messages on this connection
	 * @param store          Store to use for this Connection
	 * @param trustedPeerKey Trusted peer account key if this is a trusted
	 *                       connection, if not then null
	 * @return New Connection instance
	 * @throws IOException      If connection fails because of any IO problem
	 * @throws TimeoutException If the connection cannot be established within the
	 *                          timeout period
	 */
	public static Connection connect(InetSocketAddress socketAddress, Consumer<Message> receiveAction, AStore store,
			AccountKey trustedPeerKey) throws IOException, TimeoutException {	
		return connect(socketAddress,receiveAction,store,trustedPeerKey,Config.SOCKET_SEND_BUFFER_SIZE,Config.SOCKET_RECEIVE_BUFFER_SIZE);
	}

	/**
	 * Create a Connection by connecting to a remote address
	 *
	 * @param socketAddress    Internet Address to connect to
	 * @param receiveAction  A callback Consumer to be called for any received
	 *                       messages on this connection
	 * @param store          Store to use for this Connection
	 * @param trustedPeerKey Trusted peer account key if this is a trusted
	 *                       connection, if not then null
	 * @param sendBufferSize Size of connection send buffer in bytes
	 * @param receiveBufferSize Size of connection receive buffer in bytes
	 * @return New Connection instance
	 * @throws IOException      If connection fails because of any IO problem
	 * @throws TimeoutException If the connection cannot be established within the
	 *                          timeout period
	 */
	public static Connection connect(InetSocketAddress socketAddress, Consumer<Message> receiveAction, AStore store,
			AccountKey trustedPeerKey, int sendBufferSize, int receiveBufferSize) throws IOException, TimeoutException {
		ensureSelectorLoop();
		
		if (store == null)
			throw new Error("Connection requires a store");
		SocketChannel clientChannel = SocketChannel.open();
		clientChannel.configureBlocking(false);
		clientChannel.socket().setReceiveBufferSize(receiveBufferSize);
		clientChannel.socket().setSendBufferSize(sendBufferSize);
		
		// Disable Nagle, we don't want this as we want to send one-way traffic as fast as possible
		clientChannel.socket().setTcpNoDelay(true);
		clientChannel.connect(socketAddress);	
		
		// System.out.println("Connection: attempting to connect to: "+socketAddress);

		long start = Utils.getCurrentTimestamp();
		while (!clientChannel.finishConnect()) {
			long now = Utils.getCurrentTimestamp();
			long elapsed=now-start;
			if (elapsed > Config.DEFAULT_CLIENT_TIMEOUT)
				throw new TimeoutException("Couldn't connect after "+elapsed+"ms");
			try {
				Thread.sleep(10+elapsed/3);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Connect interrupted", e);
			}
		}

		Connection pc = create(clientChannel, receiveAction, store, trustedPeerKey);
		pc.startClientListening();
		log.trace("Connect succeeded for host: {}", socketAddress);
		return pc;
	}

	public long getReceivedCount() {
		return receiver.getReceivedCount();
	}
	
	/**
	 * Sets an optional additional message receiver hook (for debugging / observability purposes)
	 * @param hook Hook to call when a message is received
	 */
	public void setReceiveHook(Consumer<Message> hook) {
		receiver.setHook(hook);
	}

	/**
	 * Returns the remote SocketAddress associated with this connection, or null if
	 * not available
	 *
	 * @return An InetSocketAddress if associated, otherwise null
	 */
	public InetSocketAddress getRemoteAddress() {
		if (!(channel instanceof SocketChannel))
			return null;
		try {
			return (InetSocketAddress) ((SocketChannel) channel).getRemoteAddress();
		} catch (IOException e) {
			// anything fails, we have no address
			return null;
		}
	}
	
	/**
	 * Gets the store associated with this Connection
	 * @return Store instance
	 */
	public AStore getStore() {
		return store;
	}

	/**
	 * Returns the local SocketAddress associated with this connection, or null if
	 * not available
	 *
	 * @return A SocketAddress if associated, otherwise null
	 */
	public InetSocketAddress getLocalAddress() {
		if (!(channel instanceof SocketChannel))
			return null;
		try {
			return (InetSocketAddress) ((SocketChannel) channel).getLocalAddress();
		} catch (IOException e) {
			// anything fails, we have no address
			return null;
		}

	}

	/**
	 * Sends a DATA Message on this connection.
	 * 
	 * Does not send embedded values.
	 *
	 * @param data Encoded data object
	 * @return true if buffered successfully, false otherwise (not sent)
	 * @throws IOException If IO error occurs
	 */
	public boolean sendData(Blob data) throws IOException {
		log.trace("Sending data: {}", data);
		return sendBuffer(MessageType.DATA, data);
	}

	/**
	 * Sends a QUERY Message on this connection with a null Address
	 *
	 * @param form A data object representing the query form
	 * @return The ID of the message sent, or -1 if send buffer is full.
	 * @throws IOException If IO error occurs
	 */
	public long sendQuery(ACell form) throws IOException {
		return sendQuery(form, null);
	}

	/**
	 * Sends a QUERY Message on this connection.
	 *
	 * @param form    A data object representing the query form
	 * @param address The address with which to run the query, which may be null
	 * @return The ID of the message sent, or -1 if send buffer is full.
	 * @throws IOException If IO error occurs
	 */
	public long sendQuery(ACell form, Address address) throws IOException {
		AStore temp = Stores.current();
		long id = ++idCounter;
		AVector<ACell> v = Vectors.of(id, form, address);
		boolean sent = sendObject(MessageType.QUERY, v);
		return sent ? id : -1;
	}

	/**
	 * Sends a STATUS Request Message on this connection.
	 *
	 * @return The ID of the message sent, or -1 if send buffer is full.
	 * @throws IOException If IO error occurs
	 */
	public long sendStatusRequest() throws IOException {
		AStore temp = Stores.current();
		long id = ++idCounter;
		CVMLong idPayload = CVMLong.create(id);
		boolean sent=sendObject(MessageType.STATUS, idPayload);
		return sent? id:-1;
	}

	/**
	 * Sends a CHALLENGE Request Message on this connection.
	 *
	 * @param challenge Challenge a Vector that has been signed by the sending peer.
	 *
	 * @return The ID of the message sent, or -1 if the message cannot be sent.
	 *
	 * @throws IOException If IO error occurs
	 *
	 */
	public long sendChallenge(SignedData<ACell> challenge) throws IOException {
		AStore temp = Stores.current();
		try {
			long id = ++idCounter;
			boolean sent = sendObject(MessageType.CHALLENGE, challenge);
			return (sent) ? id : -1;
		} finally {
			Stores.setCurrent(temp);
		}
	}

	/**
	 * Sends a RESPONSE Request Message on this connection.
	 *
	 * @param response Signed response for the remote peer
	 * @return The ID of the message sent, or -1 if the message cannot be sent.
	 *
	 * @throws IOException If IO error occurs
	 *
	 */
	public long sendResponse(SignedData<ACell> response) throws IOException {
		AStore temp = Stores.current();
		try {
			long id = ++idCounter;
			boolean sent = sendObject(MessageType.RESPONSE, response);
			return (sent) ? id : -1;
		} finally {
			Stores.setCurrent(temp);
		}
	}

	/**
	 * Sends a transaction if possible, returning the message ID (greater than zero)
	 * if successful.
	 *
	 * Returns -1 if the message could not be sent because of a full buffer.
	 *
	 * @param signed Signed transaction
	 * @return Message ID of the transaction request, or -1 if send buffer is full.
	 * @throws IOException In the event of an IO error, e.g. closed connection
	 */
	public long sendTransaction(SignedData<ATransaction> signed) throws IOException {
		long id = getNextID();
		AVector<ACell> v = Vectors.of(id, signed);
		boolean sent = sendObject(MessageType.TRANSACT, v);
		return (sent) ? id : -1;
	}

	/**
	 * Sends a message over this connection
	 *
	 * @param msg Message to send
	 * @return true if message buffered successfully, false if failed due to full buffer
	 * @throws IOException If IO error occurs while sending
	 */
	public boolean sendMessage(Message msg) throws IOException  {
		return sendBuffer(msg.getType(),msg.getMessageData());
	}

	/**
	 * Sends a message with full payload for the given message type.
	 *
	 * @param type    Type of message
	 * @param payload Payload value for message
	 * @return true if message queued successfully, false otherwise
	 * @throws IOException If IO error occurs
	 */
	private boolean sendObject(MessageType type, ACell payload) throws IOException {
		Counters.sendCount++;

		Blob enc = Format.encodeMultiCell(payload,true);
		if (log.isTraceEnabled()) {
			log.trace("Sending message: " + type + " :: " + payload + " to " + getRemoteAddress() + " format: "
					+ Format.encodedBlob(payload).toHexString());
		}
		boolean sent = sendBuffer(type, enc);
		return sent;
	}

	/**
	 * Sends a message with the given message type and data.
	 *
	 * @param type MessageType value
	 * @param data Raw data for the message
	 * @return true if message sent, false otherwise
	 * @throws IOException
	 */
	private boolean sendBuffer(MessageType type, Blob data) throws IOException {
		// synchronise on sender
		synchronized (sender) {
			if (!sender.canSendMessage()) return false;
			int dataLength = Utils.checkedInt(data.count());
			
			// Total message length field is one byte for message code + encoded object length
			int messageLength = dataLength + 1;
			boolean sent;
			int headerLength;
			// ensure frameBuf is clear and ready for writing
			ByteBuffer frameBuf=ByteBuffer.allocate(messageLength+10);

			// write message header (length plus message code)
			Format.writeMessageLength(frameBuf, messageLength);
			frameBuf.put(type.getMessageCode());
			headerLength = frameBuf.position();

			// now write message
			frameBuf.put(headerLength, data.getInternalArray(), data.getInternalOffset(), dataLength);
			frameBuf.position(headerLength+dataLength);
			frameBuf.flip(); // ensure frameBuf is ready to write to channel

			sent = sender.bufferMessage(frameBuf);
			
			if (sent) {
				lastActivity=System.currentTimeMillis();
				if (channel instanceof SocketChannel) {
					SocketChannel chan = (SocketChannel) channel;
					// register interest in both reads and writes
					try {
						chan.register(selector, SelectionKey.OP_WRITE | SelectionKey.OP_READ, this);
					} catch (CancelledKeyException e) {
						// ignore. Must have got cancelled elsewhere?
					}
					// wake up selector if needed. TODO: do we need this?
					// if (!sender.canSendMessage()) {
					//   selector.wakeup();
					// }
				}

				if (log.isTraceEnabled()) {
					log.trace("Sent message " + type + " of length: " + dataLength + " Connection ID: "
							+ System.identityHashCode(this));
				}
			} else {
				log.warn("sendBuffer failed with message {} of length: {} Connection ID: {}"
							, type, dataLength, System.identityHashCode(this));
			}
			return sent;
		}


	}

	public synchronized void close() {
		SocketChannel chan = (SocketChannel) channel;
		if (chan != null) {
			try {
				chan.close();
			} catch (IOException e) {
				// TODO OK to ignore?
			}
		}
	}
	
	@Override
	public void finalize() {
		close();
	}

	/**
	 * Checks if this connection is closed (i.e. the underlying channel is closed)
	 *
	 * @return true if the channel is closed, false otherwise.
	 */
	public boolean isClosed() {
		return !channel.isOpen();
	}

	/**
	 * Starts listening for received events with this given peer connection.
	 * PeerConnection must have a selectable SocketChannel associated
	 *
	 * @throws IOException If IO error occurs
	 */
	private void startClientListening() throws IOException {
		SocketChannel chan = (SocketChannel) channel;
		chan.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, this);

		// seems to be needed to ensure selector sees new connection?
		selector.wakeup();
	}

	/**
	 * Selector object for all client connections
	 */
	private static Selector selector;

	private static Thread selectorThread;

	private static void ensureSelectorLoop() {
		// Double checked locking. Don't want to start this twice!
		if (selectorThread==null) {
			synchronized(Connection.class) {
				if (selectorThread==null) {
					try {
						// System.err.println("Initialising Client selector");
						selector = Selector.open();
					} catch (IOException e) {
						throw new Error("Error initialising client selector",e);
					}
					selectorThread = new Thread(selectorLoop, "Connection NIO client selector loop");
					// make this a daemon thread so it shuts down if everything else exits
					selectorThread.setDaemon(true);
					selectorThread.start();				
					
					// shut down connection loop at end of shutdown process
					Shutdown.addHook(Shutdown.CONNECTION,()->{selectorThread.interrupt();});
				}
			}
		}
	}

	private static Runnable selectorLoop = new Runnable() {
		@Override
		public void run() {
			log.trace("Client selector loop starting...");
			while (!Thread.currentThread().isInterrupted()) {
				try {
					selector.select(300);
					Set<SelectionKey> keys = selector.selectedKeys();
					Iterator<SelectionKey> it = keys.iterator();
					while (it.hasNext()) {
						final SelectionKey key = it.next();
						it.remove(); // always remove key from selection set

						// log.finest("PeerConnection key received: "+key);
						if (!key.isValid()) {
							continue;
						}

						try {
							if (key.isReadable()) {
								selectRead(key);
							} else if (key.isWritable()) {
								selectWrite(key);
							}
						} catch (ClosedChannelException e) {
							// channel was closed, just lose the key?
							log.trace("Unexpected ChannelClosedException, cancelling key: {}", e);
							key.cancel();
						} catch (IOException e) {
							log.trace("Unexpected IOException, cancelling key: {}", e);
							key.cancel();
						} catch (CancelledKeyException e) {
							log.trace("Cancelled key");
						}
					}
				} catch (IOException t) {
					log.warn("Uncaught IO error in PeerConnection client selector loop: ", t);
				}
			}
		}
	};

	/**
	 * Handles channel reads from a SelectionKey for the client listener
	 *
	 * SECURITY: Called on Connection Selector Thread
	 *
	 * @param key
	 * @throws IOException
	 */
	protected static void selectRead(SelectionKey key) throws IOException {
		Connection conn = (Connection) key.attachment();
		if (conn == null)
			throw new Error("No PeerConnection specified");
 
		try {
			int n = conn.handleChannelRecieve();
			if (n<0) {
				// Deregister interest in reading if EOS
				log.trace("Cancelled Key due to EOS");
				key.cancel();
			}
			// log.finest("Received bytes: " + n);
		} catch (ClosedChannelException e) {
			log.trace("Channel closed from: {}", conn.getRemoteAddress());
			key.cancel();
		} catch (BadFormatException e) {
			log.debug("Cancelled connection to Peer: Bad data format from: " + conn.getRemoteAddress() + " "
					+ e.getMessage());
			key.cancel();
		} catch (HandlerException e) {
			log.warn("Cancelled connection: error in handler: " +e.getMessage());
			key.cancel();
			
		}
	}

	/**
	 * Handles receipt of bytes from the channel on this Connection.
	 *
	 * Will switch the current store to the Connection-specific store if required.
	 *
	 * SECURITY: Called on NIO Thread (Server or client Connection)
	 *
	 * @return The number of bytes read from channel, or -1 if EOS
	 * @throws IOException If IO error occurs
	 * @throws BadFormatException If there is an encoding error
	 */
	public int handleChannelRecieve() throws IOException, BadFormatException, HandlerException {
		AStore savedStore = Stores.current();
		try {
			// set the current store for handling incoming messages
			Stores.setCurrent(store);
			int recd= receiver.receiveFromChannel(channel);
			int total =recd;
			while (recd>0) {
				recd=receiver.receiveFromChannel(channel);
				total+=recd;
			}
			if (recd>0) lastActivity=System.currentTimeMillis();
			return total;
		} finally {
			Stores.setCurrent(savedStore);
		}
	}

	/**
	 * Handles writes to the channel.
	 *
	 * SECURITY: Called on Selector Thread, must never block
	 *
	 * @param key Selection Key
	 * @throws IOException 
	 */
	static void selectWrite(SelectionKey key) throws IOException {
		Connection pc = (Connection) key.attachment();
		
		synchronized(pc.sender) { 
			boolean allSent = pc.sender.maybeSendBytes();
			if (allSent) {
				// deregister interest in writing
				key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
			} else {
				// we want to continue writing
			}
		}
	}

	/**
	 * Sends bytes buffered into the underlying channel.
	 * @return True if all bytes are sent, false otherwise
	 * @throws IOException If an IO Exception occurs
	 */
	public boolean flushBytes() throws IOException {
		return sender.maybeSendBytes();
	}

	@Override
	public String toString() {
		return "PeerConnection: " + channel;
	}

	public AccountKey getTrustedPeerKey() {
		return trustedPeerKey;
	}

	public void setTrustedPeerKey(AccountKey value) {
		trustedPeerKey = value;
	}

	public boolean isTrusted() {
		return trustedPeerKey != null;
	}

	public long getLastActivity() {
		return lastActivity;
	}

	public long getNextID() {
		return ++idCounter;
	}
}
