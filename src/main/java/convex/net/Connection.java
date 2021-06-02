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
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import convex.core.Result;
import convex.core.data.ACell;
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
 * <p>Class representing a Connection between network participants.</p>
 *
 * <p>Sent messages are sent asynchronously via the shared client selector.</p>
 *
 * <p>Received messages are read by the shared client selector, converted into Message instances,
 * and passed to a Consumer for handling.</p>
 *
 * <p>A Connection "owns" the ByteChannel associated with this Peer connection</p>
 */
@SuppressWarnings("unused")
public class Connection {

	final ByteChannel channel;

	/**
	 * Counter for IDs of messages sent from this JVM
	 */
	private static long idCounter = 0;

	/**
	 * Store to use for this connection. Required for responding to incoming messages.
	 */
	private final AStore store;


	private static final Logger log = Logger.getLogger(Connection.class.getName());

	// Log level for send events
	private static final Level LEVEL_SEND = Level.FINER;

	// log lever for client events
	private static final Level LEVEL_CLIENT = Level.FINER;

	/**
	 * Pre-allocated direct buffer for message sending TODO: is one per connection
	 * OK? Users should synchronise on this briefly while building message.
	 */
	private final ByteBuffer frameBuf = ByteBuffer.allocateDirect(Format.LIMIT_ENCODING_LENGTH + 20);

	private final MessageReceiver receiver;
	private final MessageSender sender;

	private Connection(ByteChannel clientChannel, Consumer<Message> receiveAction, AStore store) {
		this.channel = clientChannel;
		receiver = new MessageReceiver(receiveAction, this);
		sender = new MessageSender(clientChannel);
		this.store=store;
	}

	/**
	 * Create a PeerConnection using an existing channel. Does not perform any connection
	 * initialisation: channel should already be connected.
	 *
	 * @param channel
	 * @param receiveAction Consumer to be called when a Message is received
	 * @param store Store to use when receiving messages.
	 * @return New Connection instance
	 * @throws IOException
	 */
	public static Connection create(ByteChannel channel, Consumer<Message> receiveAction, AStore store) throws IOException {
		return new Connection(channel, receiveAction, store);
	}

	/**
	 * Create a PeerConnection by connecting to a remote address
	 *
	 * @param receiveAction A callback Consumer to be called for any received messages on this connection
	 * @return New Connection instance
	 * @throws IOException If connection fails because of any IO problem
	 */
	public static Connection connect(InetSocketAddress hostAddress, Consumer<Message> receiveAction, AStore store)
			throws IOException {
		if (store==null) throw new Error("Connection requires a store");
		SocketChannel clientChannel = SocketChannel.open(hostAddress);
		clientChannel.configureBlocking(false);

		while (!clientChannel.finishConnect()) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				throw new IOException("Connect interrupted",e);
			}
		}
		// clientChannel.setOption(StandardSocketOptions.SO_KEEPALIVE,true);
		// clientChannel.setOption(StandardSocketOptions.TCP_NODELAY,true);

		Connection pc = create(clientChannel, receiveAction, store);
		pc.startClientListening();
		log.log(LEVEL_SEND, "Connect succeeded for host: " + hostAddress);
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
	 * @throws IOException
	 */
	public InetSocketAddress getRemoteAddress() {
		if (!(channel instanceof SocketChannel)) return null;
		try {
			return (InetSocketAddress) ((SocketChannel) channel).getRemoteAddress();
		} catch (Exception e) {
			// anything fails, we have no address
			return null;
		}
	}

	/**
	 * Returns the local SocketAddress associated with this connection, or null if
	 * not available
	 *
	 * @return A SocketAddress if associated, otherwise null
	 * @throws IOException
	 */
	public InetSocketAddress getLocalAddress() {
		if (!(channel instanceof SocketChannel)) return null;
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
	 * @param value Any data object, which will be encoded and sent as a single cell
	 * @return true if buffered successfully, false otherwise (not sent)
	 * @throws IOException
	 */
	public boolean sendData(ACell value) throws IOException {
		log.log(LEVEL_SEND, "Sending data: " + Utils.toString(value));
		ByteBuffer buf = Format.encodedBuffer(value);
		return sendBuffer(MessageType.DATA, buf);
	}

	/**
	 * Sends a DATA Message on this connection.
	 *
	 * @param value Any data object
	 * @return true if buffered successfully, false otherwise (not sent)
	 * @throws IOException
	 */
	public boolean sendMissingData(Hash value) throws IOException {
		log.finer("Requested missing data for hash: "+value.toHexString()+" with store "+Stores.current());
		return sendObject(MessageType.MISSING_DATA, value);
	}

	/**
	 * Sends a QUERY Message on this connection with a null Address
	 *
	 * @param form A data object representing the query form
	 * @return The ID of the message sent, or -1 if send buffer is full.
	 * @throws IOException
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
	 * @throws IOException
	 */
	public long sendQuery(ACell form, Address address) throws IOException {
		AStore temp=Stores.current();
		try {
			long id = ++idCounter;
			AVector<ACell> v = Vectors.of(id, form, address);
			sendObject(MessageType.QUERY, v);
			return id;
		} finally {
			Stores.setCurrent(temp);
		}

	}

	/**
	 * Sends a STATUS Request Message on this connection.
	 *
	 * @return The ID of the message sent, or -1 if send buffer is full.
	 * @throws IOException
	 */
	public long sendStatusRequest() throws IOException {
		AStore temp=Stores.current();
		try {
			long id = ++idCounter;
			CVMLong idPayload=CVMLong.create(id);
			sendObject(MessageType.STATUS, idPayload);
			return id;
		} finally {
			Stores.setCurrent(temp);
		}

	}

	/**
	 * Sends a transaction if possible, returning the message ID (greater than zero)
	 * if successful.
	 *
	 * Uses the configured CLIENT_STORE to store the transaction, so that any missing data requests from the server
	 * can be honoured.
	 *
	 * Returns -1 if the message could not be sent because of a full buffer.
	 *
	 * @param signed
	 * @return Message ID of the transaction request, or -1 if send buffer is full.
	 * @throws IOException In the event of an IO error, e.g. closed connection
	 */
	public long sendTransaction(SignedData<ATransaction> signed) throws IOException {
		AStore temp=Stores.current();
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
	 * @param value Any data object
	 * @return True if buffered for sending successfully, false otherwise
	 * @throws IOException
	 */
	public boolean sendResult(CVMLong id, ACell value) throws IOException {
		return sendResult(id, value,null);
	}

	/**
	 * Sends a RESULT Message on this connection.
	 *
	 * @param result Any data object
	 * @param errorCode Error code for this result. May be null to indicate success
	 * @return True if buffered for sending successfully, false otherwise
	 * @throws IOException
	 */
	public boolean sendResult(CVMLong id, ACell value, ACell errorCode) throws IOException {
		Result result = Result.create(id, value, errorCode);
		return sendObject(MessageType.RESULT, result);
	}

	/**
	 * Sends a RESULT Message on this connection.
	 *
	 * @param result Result data structure
	 * @throws IOException
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
		if (o==null) return r;

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
	 * @return true if message buffered successfully, false if failed
	 */
	public boolean sendMessage(Message msg) throws IOException {
		return sendObject(msg.getType(), msg.getPayload());
	}

	/**
	 * Sends a payload for the given message type. Should be called on the thread that
	 * responds to missing data messages from the destination.
	 *
	 * @param type Type of message
	 * @param payload Payload value for message
	 * @return
	 * @throws IOException
	 */
	public boolean sendObject(MessageType type, ACell payload) throws IOException {
		Counters.sendCount++;

		// Need to ensure message is persisted at least, so we can respond to missing data messages
		// using the current threat store
		ACell sendVal = payload;
		ACell.createPersisted(sendVal, r -> {
			try {
				ACell data=r.getValue();
				boolean sent = sendData(data);
			} catch (IOException e) {
				throw Utils.sneakyThrow(e);
			}
		});

		ByteBuffer buf = Format.encodedBuffer(sendVal);
		log.log(LEVEL_SEND, () -> "Sending message: " + type + " :: " + payload + " to " + getRemoteAddress() + " format: "
				+ Format.encodedBlob(payload).toHexString());
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

			log.log(LEVEL_SEND, () -> "Sent message " + type + " of length: " + dataLength + " Connection ID: "
					+ System.identityHashCode(this));
		} else {
			log.log(LEVEL_SEND, () -> "Failed to send message " + type + " of length: " + dataLength + " Connection ID: "
					+ System.identityHashCode(this));
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
	 * @param peer
	 */
	public void startClientListening() throws IOException {
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

			log.log(LEVEL_CLIENT, "Client selector loop started");
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
							log.log(LEVEL_SEND, "Unexpected ChannelClosedException, cancelling key: " + e.getMessage());
							key.cancel();
						} catch (IOException e) {
							log.log(LEVEL_SEND, "Unexpected IOException, cancelling key: " + e.getMessage());
							e.printStackTrace();
							key.cancel();
						}
					}
				} catch (Throwable t) {
					log.severe("Uncaught error in PeerConnection client selector loop: " + t);
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
		if (conn == null) throw new Error("No PeerConnection specified");

		try {
			int n = conn.handleChannelRecieve();
			// log.finest("Received bytes: " + n);
		} catch (ClosedChannelException e) {
			log.log(LEVEL_CLIENT, "Channel closed from: " + conn.getRemoteAddress());
			key.cancel();
		} catch (BadFormatException e) {
			log.log(NIOServer.LEVEL_BAD_CONNECTION,"Cancelled connection to Peer: Bad data format from: " + conn.getRemoteAddress() + " " + e.getMessage());
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
	 * @throws IOException
	 * @throws BadFormatException
	 */
	public int handleChannelRecieve() throws IOException, BadFormatException {
		AStore tempStore=Stores.current();
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
	 * SECURITY: Called on Connection Selector Thread
	 *
	 * @param key
	 */
	public static void selectWrite(SelectionKey key) {
		try {
			Connection pc = (Connection) key.attachment();
			boolean moreBytes = pc.sender.maybeSendBytes();

			if (moreBytes) {
				// we want to continue writing
			} else {
				// deregister interest in writing
				key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
			}
		} catch (IOException e) {
			// TODO: figure out cases here. Probably channel closed?
			log.log(NIOServer.LEVEL_BAD_CONNECTION,e.getMessage());
			key.cancel();
		}
	}

	public boolean sendBytes() throws IOException {
		return sender.maybeSendBytes();
	}

	@Override
	public String toString() {
		return "PeerConnection: " + channel;
	}
}
