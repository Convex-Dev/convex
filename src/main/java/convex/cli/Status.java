package convex.cli;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import convex.api.Convex;
import convex.core.Result;
import convex.core.State;
import convex.core.crypto.Hash;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.AccountStatus;
import convex.core.data.BlobMap;
import convex.core.data.PeerStatus;
import convex.core.data.VectorLeaf;
import convex.core.util.Text;
import picocli.CommandLine.Command;
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

private static final Logger log = Logger.getLogger(Status.class.getName());

	@ParentCommand
	protected Main mainParent;

	@Override
	public void run() {

		int port = mainParent.getPort();
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

		Convex convex = Helpers.connect(mainParent.getHostname(), port);
		if (convex==null) {
			System.out.println("Aborting query");
			return;
		}

		try {
			Result result = convex.requestStatus().get(3000, TimeUnit.MILLISECONDS);
			AVector<ACell> resultVector = (AVector<ACell>) result.getValue();
			ABlob stateHash = (ABlob) resultVector.get(1);
			System.out.println("State hash: " + stateHash.toString());
			Hash hash = Hash.wrap(stateHash.getBytes());
			VectorLeaf stateWrapper = (VectorLeaf) convex.acquire(hash).get(3000,TimeUnit.MILLISECONDS);
			State state = (State) stateWrapper.get(0);

			state.validate();
			AVector<AccountStatus> accountList = state.getAccounts();
			BlobMap<AccountKey, PeerStatus> peerList = state.getPeers();

			System.out.println("State hash: " + stateHash.toString());
			System.out.println("Timestamp: " + state.getTimeStamp());
			System.out.println("Timestamp: " + Text.dateFormat(state.getTimeStamp().longValue()));
			System.out.println("Global Fees: " + Text.toFriendlyBalance(state.getGlobalFees().longValue()));
			System.out.println("Juice Price: " + Text.toFriendlyBalance(state.getJuicePrice().longValue()));
			System.out.println("Total Funds: " + Text.toFriendlyBalance(state.computeTotalFunds()));
			System.out.println("Number of accounts: " + accountList.size());
			System.out.println("Number of peers: " + peerList.size());
		} catch (Throwable t) {
			throw new Error("Not possible to get status information: ", t);
		}
	}

}
