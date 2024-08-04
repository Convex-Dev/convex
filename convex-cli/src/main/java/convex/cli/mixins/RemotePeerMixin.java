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
			defaultValue="${env:CONVEX_PORT:-"+Constants.DEFAULT_PEER_PORT+"",
			description="Port number to connect to a peer.")
	private Integer port;

	@Option(names={"--host"},
		defaultValue=Constants.HOSTNAME_PEER,
		description="Hostname for remote peer connection. Default: ${DEFAULT-VALUE}")
	private String hostname;

	/**
	 * Connects to a remote peer
	 * 
	 * @return
	 * @throws IOException
	 * @throws TimeoutException
	 */
	public convex.api.Convex connect() throws IOException,TimeoutException {
		if (port==null) port=convex.core.Constants.DEFAULT_PEER_PORT;
		if (hostname==null) hostname=convex.cli.Constants.HOSTNAME_PEER;
		try {
			InetSocketAddress sa=new InetSocketAddress(hostname,port);
			Convex c;
			c=Convex.connect(sa);
			
			return c;
		} catch (ConnectException ce) {
			throw new CLIError("Cannot connect to: "+hostname+" on port "+port,ce);
		} catch (TimeoutException e) {
			throw new CLIError("Timeout while attempting to connect to peer: "+hostname,e);
		}
	}

}
