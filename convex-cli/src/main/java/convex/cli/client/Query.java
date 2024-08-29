package convex.cli.client;

import convex.api.Convex;
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
	public void execute() throws InterruptedException {
		// sub command run with no command provided
		if ((commands==null)||(commands.length==0)) {
			showUsage();
			return;
		}

		Convex convex =  connectQuery();
		for (int i=0; i<commands.length; i++) {
			ACell message = Reader.read(commands[i]);
			Result result = convex.querySync(message);
			printResult(result);
			if (result.isError()) {
				break;
			}
		}
	}
}
