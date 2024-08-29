package convex.cli.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.cli.output.RecordOutput;
import convex.core.Result;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
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

	@SuppressWarnings("unchecked")
	@Override 
	public void execute() {
		Convex convex = clientConnect();
		Result result;
		result = convex.requestStatus().join();

		AVector<ACell> resultVector = (AVector<ACell>) result.getValue();
		ABlob stateHash = (ABlob) resultVector.get(1);
		// Hash hash = Hash.wrap(stateHash.getBytes());

		//AVector<AccountStatus> accountList = state.getAccounts();
		//Index<AccountKey, PeerStatus> peerList = state.getPeers();

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
	}


}
