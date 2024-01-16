package convex.cli.client;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
	description="Execute a user query.")
public class Query extends AClientCommand {

	private static final Logger log = LoggerFactory.getLogger(Query.class);

	@Parameters(paramLabel="queryCommand", description="Query Command")
	private String queryCommand;


	@Override
	public void run() {
		// sub command run with no command provided
		log.debug("query command: {}", queryCommand);

		try {
			Convex convex =  connect();
			log.debug("Executing query: %s\n", queryCommand);
			ACell message = Reader.read(queryCommand);
			Result result = convex.querySync(message, timeout);
			mainParent.printResult(result);
		} catch (IOException e) {
			throw new CLIError("IO Error executing query",e);
		} catch (TimeoutException e) {
			throw new CLIError("Query timed out");
		}
	}

}
