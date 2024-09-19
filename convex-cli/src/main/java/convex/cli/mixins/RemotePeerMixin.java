package convex.cli.mixins;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeoutException;

import convex.api.Convex;
import convex.cli.CLIError;
import convex.cli.Constants;
import picocli.CommandLine.Option;

public class RemotePeerMixin extends AMixin {
	
	@Option(names={"--port"},
			defaultValue="${env:CONVEX_PORT:-"+Constants.DEFAULT_PEER_PORT+"}",
			description="Port number to connect to host peer. Defaulting to: ${DEFAULT-VALUE}")
	private Integer port;

	@Option(names={"--host"},
		defaultValue="${env:CONVEX_HOST:-"+Constants.HOSTNAME_PEER+"}",
		description="Hostname for remote peer connection. Can specify with CONVEX_HOST. Defaulting to: ${DEFAULT-VALUE}")
	private String hostname;

	/**
	 * Connects to a remote peer
	 * 
	 * @return Convex connection instance
	 */
	public Convex connect()  {
		if (port==null) port=convex.core.Constants.DEFAULT_PEER_PORT;
		if (hostname==null) hostname=convex.cli.Constants.HOSTNAME_PEER;
		InetSocketAddress sa=new InetSocketAddress(hostname,port);
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
		}
	}

}
