package convex.net;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.function.Consumer;

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
}
