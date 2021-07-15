package convex.cli;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.Result;
import convex.core.State;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.AccountStatus;
import convex.core.data.BlobMap;
import convex.core.data.Hash;
import convex.core.data.PeerStatus;
import convex.core.store.Stores;
import convex.core.util.Text;
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
	aliases={"st"},
	mixinStandardHelpOptions=true,
	description="Reports on the current status of the network.")
public class Status implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(Status.class.getName());

	@ParentCommand
	protected Main mainParent;

	@Option(names={"--port"},
		description="Port number to connect or create a peer.")
	private int port = 0;

	@Option(names={"--host"},
		defaultValue=Constants.HOSTNAME_PEER,
		description="Hostname to connect to a peer. Default: ${DEFAULT-VALUE}")
	private String hostname;

	@SuppressWarnings("unchecked")
	@Override
	public void run() {

		if (port == 0) {
			try {
                port = Helpers.getSessionPort(mainParent.getSessionFilename());
			} catch (IOException e) {
				log.warn("Cannot load the session control file");
			}
		}
		if (port == 0) {
			log.warn("Cannot find a local port or you have not set a valid port number");
			return;
		}

		Convex convex = null;
		try {
			convex = mainParent.connectToSessionPeer(hostname, port, Main.initConfig.getUserAddress(0), Main.initConfig.getUserKeyPair(0));
		} catch (Throwable t) {
			log.error(t.getMessage());
			return;
		}

		try {
			Result result = convex.requestStatus().get(3000, TimeUnit.MILLISECONDS);
			AVector<ACell> resultVector = (AVector<ACell>) result.getValue();
			ABlob stateHash = (ABlob) resultVector.get(1);
			System.out.println("State hash: " + stateHash.toString());
			Hash hash = Hash.wrap(stateHash.getBytes());
			AVector<ACell> stateWrapper = (AVector<ACell>) convex.acquire(hash, Stores.current()).get(3000,TimeUnit.MILLISECONDS);
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
