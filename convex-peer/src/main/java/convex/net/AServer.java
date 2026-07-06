package convex.net;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.function.Consumer;

import convex.core.message.AConnection;
import convex.core.message.Message;

/**
 * Base class for servers that Listen for lattice protocol messages and call a receive action
 */
public abstract class AServer implements Closeable {

	/**
	 * Gets the port that this server instance is configured to listen on
	 * 
	 * @return Port number, may be null if not set
	 */
	public Integer getPort() {
		return port;
	}

	private Integer port=null;
	
	@Override
	public abstract void close();

	public abstract InetSocketAddress getHostAddress();

	/**
	 * Sets the port for this server. Should be called prior to launch
	 * @param port
	 */
	public void setPort(Integer port) {
		this.port=port;
	}

	/**
	 * Launch the Server as currently configured
	 * @throws IOException If an IO error occurs, e.g. binding to configured port
	 * @throws InterruptedException If the operation was interrupted
	 */
	public abstract void launch() throws IOException, InterruptedException;

	/**
	 * Get the receiver action for the server, which handles an incoming Message
	 * Receive action is responsible for all message handling
	 *
	 * @return Receive action
	 */
	public abstract Consumer<Message> getReceiveAction();

	/**
	 * Set the receiver action for the server. Must be called before launch.
	 *
	 * @param action Receive action to handle incoming messages
	 */
	public abstract void setReceiveAction(Consumer<Message> action);

	/** No-op disconnect action, used as the default and the null-reset value. */
	private static final Consumer<AConnection> NO_DISCONNECT = c -> {};

	/**
	 * Action invoked when an inbound connection closes, allowing the owner to release any
	 * per-connection state eagerly (#566). Default is a no-op; transports that can detect
	 * disconnects (e.g. Netty via {@code channelInactive}) invoke it. Not all transports
	 * surface disconnects, so callers must not rely on it firing for every close — it is an
	 * optimisation over periodic cleanup, not a guarantee.
	 */
	private Consumer<AConnection> disconnectAction = NO_DISCONNECT;

	/**
	 * Sets the action invoked when an inbound connection closes. Should be called before launch.
	 *
	 * @param action Disconnect action (null resets to a no-op)
	 */
	public void setDisconnectAction(Consumer<AConnection> action) {
		this.disconnectAction = (action != null) ? action : NO_DISCONNECT;
	}

	/**
	 * Gets the current disconnect action (never null).
	 * @return Disconnect action
	 */
	public Consumer<AConnection> getDisconnectAction() {
		return disconnectAction;
	}

	/**
	 * Returns the number of active inbound client connections.
	 * @return Connection count, or -1 if not available
	 */
	public int getClientConnectionCount() {
		return -1;
	}
}
