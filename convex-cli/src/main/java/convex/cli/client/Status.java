package convex.cli.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.cli.CLIError;
import convex.cli.output.RecordOutput;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.Keyword;
import convex.peer.API;
import picocli.CommandLine.Command;

/**
 *
 * Convex Status sub command
 *
 *		convex.status
 *
 */
@Command(name="status",
	mixinStandardHelpOptions=true,
	description="Reports on the current status of the network.")
public class Status extends AClientCommand {

	protected static final Logger log = LoggerFactory.getLogger(Status.class);

	@Override
	public void execute() throws InterruptedException {
		try (Convex convex = clientConnect()) {
			Result result = convex.requestStatusSync();
			if (result.isError()) {
				throw new CLIError("Status request failed: "+result);
			}

			AMap<Keyword,ACell> status = API.ensureStatusMap(result.getValue());

			if (status==null) {
				throw new CLIError(convex.toString()+" did not return a valid status, was : "+result);
			}

			RecordOutput output=new RecordOutput();
			int n=API.STATUS_KEYS.size();
			for (int i=0; i<n; i++) {
				Keyword k=API.STATUS_KEYS.get(i);
				output.addField(k, status.get(k));
			}
			mainParent.printRecord(output);
		}
	}


}
