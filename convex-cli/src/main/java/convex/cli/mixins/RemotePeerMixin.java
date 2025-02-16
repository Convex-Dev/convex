package convex.cli.mixins;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeoutException;

import convex.api.Convex;
import convex.cli.CLIError;
import convex.cli.Constants;
import convex.net.IPUtils;
import picocli.CommandLine.Option;

public class RemotePeerMixin extends AMixin {
	
	@Option(names={"--port"},
			defaultValue="${env:CONVEX_PORT:-"+Constants.DEFAULT_PEER_PORT+"}",
			description="Port number to connect to remote peer. Defaulting to: ${DEFAULT-VALUE}")
	private Integer port;

	@Option(names={"--host"},
		defaultValue="${env:CONVEX_HOST}",
		description="Hostname for remote peer connection. Can specify with CONVEX_HOST, or use \"none\" to disable.")
	private String hostname;

	/**
	 * Connects to a remote peer
	 * 
	 * @return Convex connection instance
	 */
	public Convex connect()  {
		InetSocketAddress sa=getSocketAddress();
		try {
			Convex c;
			c=Convex.connect(sa);
			
			return c;
		} catch (ConnectException ce) {
			throw new CLIError("Cannot connect to host: "+sa,ce);
		} catch (TimeoutException e) {
			throw new CLIError("Timeout while attempting to connect to peer: "+hostname,e);
		} catch (IOException e) {
			throw new CLIError("IO Error: "+e.getMessage(),e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new CLIError("Connection interrupted",e);
		}
	}
	
	public InetSocketAddress getSocketAddress() {
		int port= (this.port!=null) ?this.port:convex.core.Constants.DEFAULT_PEER_PORT;
		String hostname=(this.hostname!=null)?this.hostname:convex.cli.Constants.DEFAULT_PEER_HOSTNAME;
		InetSocketAddress sa=IPUtils.parseAddress(hostname,port);
		return sa;
	}

	/**
	 * Gets the socket address for the remote peer, or null if not specified in CLI
	 * @return Socket address instance, or null if not specified at CLI
	 */
	public InetSocketAddress getSpecifiedSource() {
		if (hostname==null) return null;
		if (hostname.trim().equalsIgnoreCase("none")) return null;
		return getSocketAddress();
	}

}
