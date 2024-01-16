package convex.cli.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeoutException;

import convex.api.Convex;
import convex.cli.ATopCommand;
import convex.cli.Constants;
import convex.core.data.Address;
import picocli.CommandLine.Option;

public abstract class AClientCommand extends ATopCommand {
	@Option(names={"--timeout"},
			description="Timeout in miliseconds.")
	protected long timeout = Constants.DEFAULT_TIMEOUT_MILLIS;

	@Option(names={"-a", "--address"},
			defaultValue="${env:CONVEX_ADDRESS}",
			description = "Account address to use. Default: ${DEFAULT-VALUE}")
	protected String addressValue = null;
	
	@Option(names={"--port"},
			defaultValue="${env:CONVEX_PORT}",
			description="Port number to connect to a peer.")
	private Integer port;

	@Option(names={"--host"},
		defaultValue=Constants.HOSTNAME_PEER,
		description="Hostname to connect to a peer. Default: ${DEFAULT-VALUE}")
	private String hostname;

	
	public Address getUserAddress() {
		Address result= Address.parse(addressValue);	
		return result;
	}

	protected convex.api.Convex connect() throws IOException,TimeoutException {
		if (port==null) port=convex.core.Constants.DEFAULT_PEER_PORT;
		try {
			Convex c;
			c=Convex.connect(new InetSocketAddress("localhost",port));
			return c;
		} catch (Exception e) {
			throw e;
		}
	}
}
