package convex.net;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;

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

	public abstract void launch() throws IOException, InterruptedException;
}
