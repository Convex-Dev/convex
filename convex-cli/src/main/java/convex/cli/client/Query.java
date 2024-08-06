package convex.cli.client;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import convex.api.Convex;
import convex.cli.CLIError;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.lang.Reader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 *
 * Convex Query sub command
 *
 * 		convex.query
 *
 */
@Command(name="query",
	mixinStandardHelpOptions=true,
	description="Execute user queries. ")
public class Query extends AClientCommand {

	@Parameters(
			paramLabel="queryCommand", 
			description="Query command(s). Multiple commands will be executed in sequence unless one fails.")
	private String[] commands;

	@Override
	public void run() {
		// sub command run with no command provided
		if ((commands==null)||(commands.length==0)) {
			showUsage();
			return;
		}

		try {
			Convex convex =  connectQuery();
			for (int i=0; i<commands.length; i++) {
				ACell message = Reader.read(commands[i]);
				Result result = convex.querySync(message);
				printResult(result);
				if (result.isError()) {
					break;
				}
			}
		} catch (IOException e) {
			throw new CLIError("IO Error executing query",e);
		} catch (TimeoutException e) {
			throw new CLIError("Query timed out. Perhaps there is a network problem, or the host is not an operational Convex peer?");
		}
	}
}
