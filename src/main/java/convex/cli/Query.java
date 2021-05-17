package convex.cli;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeoutException;

import convex.api.Convex;
import convex.core.Init;
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

	@ParentCommand
	protected Main mainParent;


	@Option(names={"-p", "--port"},
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
		System.out.printf("query command %s\n", queryCommand);
		Convex convex = Helpers.connect(hostname, port);
		if (convex==null) {
			System.out.println("Aborting query");
			return;
		}
		try {
			System.out.printf("Executing query: %s\n", queryCommand);
			ACell exp=Reader.read(queryCommand);
			Result result=convex.querySync(exp, 5000);
			System.out.println("Result received:");
			System.out.println(result);
		} catch (IOException e) {
			System.out.printf("Query Error: %s\n", e.getMessage());
			// TODO Auto-generated catch block
			e.printStackTrace();
			return;
		}  catch (TimeoutException e) {
			System.out.println("Query timeout");
		}
	}

}
