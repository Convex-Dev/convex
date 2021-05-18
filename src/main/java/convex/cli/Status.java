package convex.cli;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import convex.api.Convex;
import convex.core.Result;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
*
* Convex Status sub command
*
*/
@Command(name="status",
	mixinStandardHelpOptions=true,
	description="Reports on the current status of the network.")
public class Status implements Runnable {

	@ParentCommand
	protected Main mainParent;

	@Option(names={"-p", "--port"},
		description="Specify a port of a local peer.")
	protected int port;

	@Option(names={"--host"},
		defaultValue=Constants.HOSTNAME_PEER,
		description="Hostname to local peer. Default: ${DEFAULT-VALUE}")
	String hostname;

	@Override
	public void run() {
		// sub command run with no command provided
		System.out.println("status command");
		Convex convex = Helpers.connect(hostname, port);
		if (convex==null) {
			System.out.println("Aborting query");
			return;
		}

		try {
			Future<Result> cf = convex.requestStatus();
			Result result = cf.get(50000, TimeUnit.MILLISECONDS);
			System.out.println(result);
		} catch (ExecutionException | InterruptedException | TimeoutException | IOException e) {
			throw new Error("Not possible? Since there is no Thread for the future....", e);
		}
	}

}
