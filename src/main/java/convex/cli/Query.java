package convex.cli;

import java.io.IOException;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import convex.api.Convex;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.lang.Reader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
*
* Convex Query sub command
*
*/
@Command(name="query",
	mixinStandardHelpOptions=true,
	description="Execute a query on the current peer.")
public class Query implements Runnable {

	private static final Logger log = Logger.getLogger(Query.class.getName());

	@ParentCommand
	protected Main mainParent;


	@Option(names={"-p", "--port"},
		defaultValue="0",
		description="Port address to peer.")
	private int port;

	@Option(names={"--host"},
		defaultValue=Constants.HOSTNAME_PEER,
		description="Hostname to local peer. Default: ${DEFAULT-VALUE}")
	private String hostname;

	@Parameters(paramLabel="queryCommand", description="Query Command")
	private String queryCommand;

	@Override
	public void run() {
		// sub command run with no command provided
		log.info("query command: "+queryCommand);
		if (port == 0) {
			try {
				port = Helpers.getSessionPort(mainParent.getSessionFilename());
			} catch (IOException e) {
				log.warning("Cannot load the session control file");
			}
		}
		if (port == 0) {
			log.warning("Cannot find a local port or you have not set a valid port number");
			return;
		}

		Convex convex = Helpers.connect(hostname, port);
		if (convex==null) {
			log.severe("Cannot connect to a peer");
			return;
		}
		try {
			System.out.printf("Executing query: %s\n", queryCommand);
			ACell exp=Reader.read(queryCommand);
			Result result=convex.querySync(exp, 5000);
			System.out.println(result);
		} catch (IOException e) {
			log.severe("Query Error: "+e.getMessage());
			// TODO Auto-generated catch block
			e.printStackTrace();
			return;
		}  catch (TimeoutException e) {
			log.severe("Query timeout");
		}
	}

}
