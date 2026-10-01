package convex.cli.mixins;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.TimeoutException;

import convex.api.Convex;
import convex.cli.CLIError;
import convex.cli.Constants;
import convex.cli.ExitCodes;
import convex.net.IPUtils;
import convex.net.Transports;
import picocli.CommandLine.Option;

public class RemotePeerMixin extends AMixin {
	
	@Option(names={"--port"},
			defaultValue="${env:CONVEX_PORT}",
			description="Remote peer port (defaults to the endpoint port or 18888).")
	private Integer port;

	@Option(names={"--host"},
		defaultValue="${env:CONVEX_HOST}",
		description="Remote hostname or tcp:// or tls:// endpoint (default: peer.convex.live). Can specify with CONVEX_HOST environment variable, or use \"none\" to disable.")
	private String hostname;

	/**
	 * Connects to a remote peer
	 * 
	 * @return Convex connection instance
	 */
	public Convex connect()  {
		URI endpoint=getEndpoint();
		if (hostname==null) {
			// No --host / CONVEX_HOST given, so we are defaulting to the production Protonet
			// peer. Surface it so a client command never *silently* targets production (#582).
			inform(1, "No --host specified; connecting to production Protonet peer "
					+ Constants.DEFAULT_PEER_HOSTNAME + " (override with --host or CONVEX_HOST)");
		}
		try {
			Convex c;
			c=Convex.connect(endpoint);
			
			return c;
		} catch (ConnectException ce) {
			throw new CLIError("Cannot connect to host: "+endpoint,ce);
		} catch (TimeoutException e) {
			throw new CLIError("Timeout while attempting to connect to peer: "+hostname,e);
		} catch (IOException e) {
			throw new CLIError("IO Error: "+e.getMessage(),e);
		} catch (IllegalArgumentException e) {
			throw new CLIError(ExitCodes.USAGE,e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new CLIError("Connection interrupted",e);
		}
	}
	
	public InetSocketAddress getSocketAddress() {
		return IPUtils.toInetSocketAddress(getEndpoint());
	}

	/** Preserves the transport scheme, with an explicit --port overriding the URI. */
	public URI getEndpoint() {
		if ((this.hostname!=null)&&this.hostname.trim().equalsIgnoreCase("none")) {
			throw new CLIError(ExitCodes.USAGE,"Peer connection disabled with '--host none', but this command requires a peer connection");
		}
		try {
			URI endpoint=Transports.endpoint(getHostname());
			if (port==null) return endpoint;
			if (port<0 || port>65535) throw new IllegalArgumentException("Invalid port");
			return new URI(endpoint.getScheme(),null,endpoint.getHost(),port,
				endpoint.getPath(),endpoint.getQuery(),null);
		} catch (IllegalArgumentException | URISyntaxException e) {
			throw new CLIError(ExitCodes.USAGE,"Invalid peer endpoint: "+getHostname());
		}
	}

	/**
	 * Gets the hostname specified for the remote peer, or the default peer hostname.
	 * May include a scheme and/or port, e.g. "tls://peer.example.com:18889"
	 * @return Hostname string
	 */
	public String getHostname() {
		return (hostname!=null)?hostname.trim():Constants.DEFAULT_PEER_HOSTNAME;
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

	/** Explicit sync source, retaining its transport. */
	public URI getSpecifiedEndpoint() {
		if (hostname==null || hostname.trim().equalsIgnoreCase("none")) return null;
		return getEndpoint();
	}

}
