package convex.cli.client;

import convex.cli.Constants;
import convex.cli.Main;
import convex.core.data.Address;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

public abstract class AClientCommand implements Runnable {
	@Option(names={"--timeout"},
			description="Timeout in miliseconds.")
	protected long timeout = Constants.DEFAULT_TIMEOUT_MILLIS;

	@Option(names={"-a", "--address"},
			defaultValue="${env:CONVEX_ADDRESS}",
			description = "Account address to use. Default: ${DEFAULT-VALUE}")
	protected String addressValue = null;
	
	@Option(names={"--port"},
			description="Port number to connect to a peer.")
		private int port = 0;

	@Option(names={"--host"},
		defaultValue=Constants.HOSTNAME_PEER,
		description="Hostname to connect to a peer. Default: ${DEFAULT-VALUE}")
	private String hostname;

	@ParentCommand
	protected Main mainParent;

	
	public Address getUserAddress() {
		Address result= Address.parse(addressValue);	
		return result;
	}

	protected convex.api.Convex connect() {
		return mainParent.connect();
	}
}
