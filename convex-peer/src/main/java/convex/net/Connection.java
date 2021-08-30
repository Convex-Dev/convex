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
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.IRefFunction;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.transactions.ATransaction;
import convex.core.util.Counters;
import convex.core.util.Utils;

/**
 * <p>
 * Class representing a Connection between network participants.
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
	 * Counter for IDs of all messages sent from this JVM
	 */
	private static long idCounter = 0;

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

	/**
	 * Pre-allocated direct buffer for message sending TODO: is one per connection
	 * OK? Users should synchronise on this briefly while building message.
	 */
	private final ByteBuffer frameBuf = ByteBuffer.allocateDirect(Format.LIMIT_ENCODING_LENGTH + 20);

	private final MessageReceiver receiver;
	private final MessageSender sender;

	private Connection(ByteChannel clientChannel, Consumer<Message> receiveAction, AStore store,
			AccountKey trustedPeerKey) {
		this.channel = clientChannel;
		receiver = new MessageReceiver(receiveAction, this);
		sender = new MessageSender(clientChannel);
		this.store = store;
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
		return new Connection(channel, receiveAction, store, trustedPeerKey);
	}
	
	/**
	 * Gets the global message ID counter
	 * @return Message ID counter for last message sent
	 */
	public static long getCounter() {
		return idCounter;
	}

	/**
	 * Create a PeerConnection by connecting to a remote address
	 *
	 * @param hostAddress   Internet Address to connect to
	 * @param receiveAction A callback Consumer to be called for any received
	 *                      messages on this connection
	 * @param store         Store to use for this Connection
	 * @return New Connection instance
	 * @throws IOException      If connection fails because of any IO problem
	 * @throws TimeoutException If connection cannot be established within an
	 *                          acceptable time (~5s)
	 */
	public static Connection connect(InetSocketAddress hostAddress, Consumer<Message> receiveAction, AStore store)
			throws IOException, TimeoutException {
		return connect(hostAddress, receiveAction, store, null);
	}
	
	/**
	 * Create a Connection by connecting to a remote address
	 *
	 * @param hostAddress    Internet Address to connect to
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
	public static Connection connect(InetSocketAddress hostAddress, Consumer<Message> receiveAction, AStore store,
			AccountKey trustedPeerKey) throws IOException, TimeoutException {	
		return connect(hostAddress,receiveAction,store,trustedPeerKey,Constants.SOCKET_SEND_BUFFER_SIZE,Constants.SOCKET_RECEIVE_BUFFER_SIZE);
	}

	/**
	 * Create a Connection by connecting to a remote address
	 *
	 * @param hostAddress    Internet Address to connect to
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
	public static Connection connect(InetSocketAddress hostAddress, Consumer<Message> receiveAction, AStore store,
			AccountKey trustedPeerKey, int sendBufferSize, int receiveBufferSize) throws IOException, TimeoutException {
		if (store == null)
			throw new Error("Connection requires a store");
		SocketChannel clientChannel = SocketChannel.open();
		clientChannel.configureBlocking(false);
		clientChannel.socket().setReceiveBufferSize(receiveBufferSize);
		clientChannel.socket().setSendBufferSize(sendBufferSize);
		
		// TODO: reconsider this
		clientChannel.socket().setTcpNoDelay(true);
		clientChannel.connect(hostAddress);	

		long start = Utils.getCurrentTimestamp();
		while (!clientChannel.finishConnect()) {
			long now = Utils.getCurrentTimestamp();
			long elapsed=now-start;
			if (elapsed > Constants.DEFAULT_CLIENT_TIMEOUT)
				throw new TimeoutException("Couldn't connect");
			try {
				Thread.sleep(10+elapsed/5);
			} catch (InterruptedException e) {
				throw new IOException("Connect interrupted", e);
			}
		}

		Connection pc = create(clientChannel, receiveAction, store, trustedPeerKey);
		pc.startClientListening();
		log.debug("Connect succeeded for host: {}", hostAddress);
		return pc;
	}

	public long getReceivedCount() {
		return receiver.getReceivedCount();
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
		} catch (Exception e) {
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
		} catch (Exception e) {
			// anything fails, we have no address
			return null;
		}

	}

	/**
	 * Sends a DATA Message on this connection.
	 * 
	 * Does not send embedded values.
	 *
	 * @param value Any data object, which will be encoded and sent as a single cell
	 * @return true if buffered successfully, false otherwise (not sent)
	 * @throws IOException If IO error occurs
	 */
	public boolean sendData(ACell value) throws IOException {
		log.trace("Sending data: {}", value);
		ByteBuffer buf = Format.encodedBuffer(value);
		return sendBuffer(MessageType.DATA, buf);
	}

	/**
	 * Sends a DATA Message on this connection.
	 *
	 * @param value Any data object
	 * @return true if buffered successfully, false otherwise (not sent)
	 * @throws IOException If IO error occurs
	 */
	public boolean sendMissingData(Hash value) throws IOException {
		log.trace("Requested missing data for hash {} with store {}", value.toHexString(), Stores.current());
		return sendObject(MessageType.MISSING_DATA, value);
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
		try {
			long id = ++idCounter;
			AVector<ACell> v = Vectors.of(id, form, address);
			boolean sent = sendObject(MessageType.QUERY, v);
			return sent ? id : -1;
		} finally {
			Stores.setCurrent(temp);
		}

	}

	/**
	 * Sends a STATUS Request Message on this connection.
	 *
	 * @return The ID of the message sent, or -1 if send buffer is full.
	 * @throws IOException If IO error occurs
	 */
	public long sendStatusRequest() throws IOException {
		AStore temp = Stores.current();
		try {
			long id = ++idCounter;
			CVMLong idPayload = CVMLong.create(id);
			sendObject(MessageType.STATUS, idPayload);
			return id;
		} finally {
			Stores.setCurrent(temp);
		}
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
	 * Uses the configured CLIENT_STORE to store the transaction, so that any
	 * missing data requests from the server can be honoured.
	 *
	 * Returns -1 if the message could not be sent because of a full buffer.
	 *
	 * @param signed Signed transaction
	 * @return Message ID of the transaction request, or -1 if send buffer is full.
	 * @throws IOException In the event of an IO error, e.g. closed connection
	 */
	public long sendTransaction(SignedData<ATransaction> signed) throws IOException {
		AStore temp = Stores.current();
		try {
			Stores.setCurrent(store);
			long id = ++idCounter;
			AVector<ACell> v = Vectors.of(id, signed);
			boolean sent = sendObject(MessageType.TRANSACT, v);
			return (sent) ? id : -1;
		} finally {
			Stores.setCurrent(temp);
		}
	}

	/**
	 * Sends a RESULT Message on this connection with no error code (i.e. a success)
	 *
	 * @param id    ID for result message
	 * @param value Any data object
	 * @return True if buffered for sending successfully, false otherwise
	 * @throws IOException If IO error occurs
	 */
	public boolean sendResult(CVMLong id, ACell value) throws IOException {
		return sendResult(id, value, null);
	}

	/**
	 * Sends a RESULT Message on this connection.
	 *
	 * @param id        ID for result message
	 * @param value     Any data object
	 * @param errorCode Error code for this result. May be null to indicate success
	 * @return True if buffered for sending successfully, false otherwise
	 * @throws IOException In case of IO Error
	 */
	public boolean sendResult(CVMLong id, ACell value, ACell errorCode) throws IOException {
		Result result = Result.create(id, value, errorCode);
		return sendObject(MessageType.RESULT, result);
	}

	/**
	 * Sends a RESULT Message on this connection.
	 *
	 * @param result Result data structure
	 * @return true if message queued successfully, false otherwise
	 * @throws IOException If IO error occurs
	 */
	public boolean sendResult(Result result) throws IOException {
		return sendObject(MessageType.RESULT, result);
	}

	private IRefFunction sender() {
		return sendAll;
	}

	private final IRefFunction sendAll = (r -> {
		// TODO: halt conditions to prevent sending the whole universe
		ACell o = r.getValue();
		if (o == null)
			return r;

		// send children first
		o.updateRefs(sender());

		// only send this value if not embedded
		if (!o.isEmbedded()) {
			try {
				sendData(o);
			} catch (IOException e) {
				throw Utils.sneakyThrow(e);
			}
		}

		return r;
	});

	/**
	 * Sends a message over this connection
	 *
	 * @param msg Message to send
	 * @return true if message buffered successfully, false if failed
	 * @throws IOException If IO error occurs
	 */
	public boolean sendMessage(Message msg) throws IOException {
		return sendObject(msg.getType(), msg.getPayload());
	}

	/**
	 * Sends a payload for the given message type. Should be called on the thread
	 * that responds to missing data messages from the destination.
	 *
	 * @param type    Type of message
	 * @param payload Payload value for message
	 * @return true if message queued successfully, false otherwise
	 * @throws IOException If IO error occurs
	 */
	public boolean sendObject(MessageType type, ACell payload) throws IOException {
		Counters.sendCount++;

		// Need to ensure message is persisted at least, so we can respond to missing
		// data messages
		// using the current thread store
		ACell sendVal = payload;
		ACell.createPersisted(sendVal, r -> {
			try {
				ACell data = r.getValue();
				if (!Format.isEmbedded(data)) sendData(data);
			} catch (IOException e) {
				throw Utils.sneakyThrow(e);
			}
		});

		ByteBuffer buf = Format.encodedBuffer(sendVal);
		if (log.isTraceEnabled()) {
			log.trace("Sending message: " + type + " :: " + payload + " to " + getRemoteAddress() + " format: "
					+ Format.encodedBlob(payload).toHexString());
		}
		boolean sent = sendBuffer(type, buf);
		return sent;
	}

	/**
	 * Sends a message with the given message type and data buffer.
	 *
	 * @param type MessageType value
	 * @param buf  Buffer containing raw wire data for the message
	 * @return true if message sent, false otherwise
	 * @throws IOException
	 */
	private boolean sendBuffer(MessageType type, ByteBuffer buf) throws IOException {
		int dataLength = buf.remaining();

		// Total length field is message code + encoded object length
		int messageLength = dataLength + 1;
		boolean sent;
		int headerLength;

		// synchronize in case we are sending messages from different threads
		// This is OK but need to avoid corrupted messages.
		synchronized (frameBuf) {
			// ensure frameBuf is clear and ready for writing
			frameBuf.clear();

			// write message header
			Format.writeMessageLength(frameBuf, messageLength);
			frameBuf.put(type.getMessageCode());
			headerLength = frameBuf.position();

			// now write message
			frameBuf.put(buf);
			frameBuf.flip(); // ensure frameBuf is ready to write to channel

			sent = sender.bufferMessage(frameBuf);
		}

		if (sent) {
			if (channel instanceof SocketChannel) {
				SocketChannel chan = (SocketChannel) channel;
				// register interest in both reads and writes
				try {
					chan.register(selector, SelectionKey.OP_WRITE | SelectionKey.OP_READ, this);
				} catch (CancelledKeyException e) {
					// ignore. Must have got cancelled elsewhere?
				}
				// wake up selector
				selector.wakeup();
			}

			if (log.isTraceEnabled()) {
				log.trace("Sent message " + type + " of length: " + dataLength + " Connection ID: "
						+ System.identityHashCode(this));
			}
		} else {
			log.debug("sendBuffer failed with message {} of length: {} Connection ID: {}"
						, type, dataLength, System.identityHashCode(this));
		}
		return sent;
	}

	public synchronized void close() {
		if (channel != null) {
			try {
				channel.close();
			} catch (IOException e) {
				// TODO OK to ignore?
			}
		}
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

		// start running selector loop after we register for reading!
		ensureSelectorLoop();

		// seems to be needed to ensure selector sees new connection?
		selector.wakeup();
	}

	private static final Selector selector;

	static {
		try {
			selector = Selector.open();
		} catch (IOException e) {
			throw new Error(e);
		}
	}

	public void wakeUp() {
		selector.wakeup();
	}

	private static Thread loopThread;

	private static void ensureSelectorLoop() {
		// double checked initialisation
		if (loopThread == null) {
			synchronized (Connection.class) {
				if (loopThread == null) {
					loopThread = new Thread(selectorLoop, "PeerConnection NIO client selector loop");
					// make this a daemon thread so it shuts down if everything else exits
					loopThread.setDaemon(true);
					loopThread.start();
				}
			}
		}
	}

	private static Runnable selectorLoop = new Runnable() {
		@Override
		public void run() {

			log.debug("Client selector loop started");
			while (true) {
				try {
					selector.select(1000);
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
							log.debug("Unexpected ChannelClosedException, cancelling key: {}", e);
							key.cancel();
						} catch (IOException e) {
							log.debug("Unexpected IOException, cancelling key: {}", e);
							key.cancel();
						} catch (CancelledKeyException e) {
							log.debug("Cancelled key");
						}
					}
				} catch (Throwable t) {
					log.error("Uncaught error in PeerConnection client selector loop: {}", t);
					t.printStackTrace();
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
			// log.finest("Received bytes: " + n);
		} catch (ClosedChannelException e) {
			log.debug("Channel closed from: {}", conn.getRemoteAddress());
			key.cancel();
		} catch (BadFormatException e) {
			log.warn("Cancelled connection to Peer: Bad data format from: " + conn.getRemoteAddress() + " "
					+ e.getMessage());
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
	 * @return The number of bytes read from channel
	 * @throws IOException If IO error occurs
	 * @throws BadFormatException If there is an encoding error
	 */
	public int handleChannelRecieve() throws IOException, BadFormatException {
		AStore tempStore = Stores.current();
		try {
			// set the current store for handling incoming messages
			Stores.setCurrent(store);
			return receiver.receiveFromChannel(channel);
		} finally {
			Stores.setCurrent(tempStore);
		}
	}

	/**
	 * Handles writes to the channel.
	 *
	 * SECURITY: Called on Selector Thread
	 *
	 * @param key Selection Key
	 * @throws IOException 
	 */
	static void selectWrite(SelectionKey key) throws IOException {
		Connection pc = (Connection) key.attachment();
		boolean allSent = pc.sender.maybeSendBytes();

		if (allSent) {
			// deregister interest in writing
			key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
		} else {
			// we want to continue writing
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
}
