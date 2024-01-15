package convex.cli.client;

import convex.cli.Constants;
import picocli.CommandLine.Option;

public abstract class AClientCommand implements Runnable {
	@Option(names={"--timeout"},
			description="Timeout in miliseconds.")
	protected long timeout = Constants.DEFAULT_TIMEOUT_MILLIS;

	@Option(names={"-a", "--address"},
			defaultValue="${env:CONVEX_ADDRESS}",
			description = "Account address to use. Default: ${DEFAULT-VALUE}")
	protected long address = 11;

}
