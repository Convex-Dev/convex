package convex.cli;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.cli.output.RecordOutput;
import convex.cli.peer.SessionItem;
import convex.core.Result;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Hash;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

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
public class Status implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(Status.class);

	@ParentCommand
	protected Main mainParent;

	@Option(names={"--port"},
		description="Port number to connect or create a peer.")
	private int port = 0;

	@Option(names={"--host"},
		defaultValue=Constants.HOSTNAME_PEER,
		description="Hostname to connect to a peer. Default: ${DEFAULT-VALUE}")
	private String hostname;

	@Option(names={"-t", "--timeout"},
		description="Timeout in miliseconds.")
	private long timeout = Constants.DEFAULT_TIMEOUT_MILLIS;

	@SuppressWarnings("unchecked")
	@Override
	public void run() {

		if (port == 0) {
			try {
                SessionItem item = Helpers.getSessionItem(mainParent.getSessionFilename());
				port = item.getPort();
			} catch (IOException e) {
				log.warn("Cannot load the session control file");
			}
		}
		if (port == 0) {
			log.warn("Cannot find a local port or you have not set a valid port number");
			return;
		}

		Convex convex = null;
		convex = mainParent.connectAsPeer(0);

		try {
			Result result = convex.requestStatus().get(timeout, TimeUnit.MILLISECONDS);
			AVector<ACell> resultVector = (AVector<ACell>) result.getValue();
			ABlob stateHash = (ABlob) resultVector.get(1);
			Hash hash = Hash.wrap(stateHash.getBytes());

			//AVector<AccountStatus> accountList = state.getAccounts();
			//BlobMap<AccountKey, PeerStatus> peerList = state.getPeers();

			RecordOutput output=new RecordOutput();
			output.addField("State hash", stateHash.toString());
			//output.addField("Timestamp",state.getTimeStamp().toString());
			//output.addField("Timestamp value", Text.dateFormat(state.getTimeStamp().longValue()));
			//output.addField("Global Fees", Text.toFriendlyBalance(state.getGlobalFees().longValue()));
			//output.addField("Juice Price", Text.toFriendlyBalance(state.getJuicePrice().longValue()));
			//output.addField("Total Funds", Text.toFriendlyBalance(state.computeTotalFunds()));
			//output.addField("Number of accounts", accountList.size());
			//output.addField("Number of peers", peerList.size());
			mainParent.printRecord(output);
		} catch (Exception e) {
			throw new CLIError("Error getting network status",e);
		}
	}

}
