package convex.cli;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
			mainParent.showError(t);
			return;
		}

		try {
			Result result = convex.requestStatus().get(timeout, TimeUnit.MILLISECONDS);
			AVector<ACell> resultVector = (AVector<ACell>) result.getValue();
			ABlob stateHash = (ABlob) resultVector.get(1);
			Hash hash = Hash.wrap(stateHash.getBytes());
			AVector<ACell> stateWrapper = (AVector<ACell>) convex.acquire(hash, Stores.current()).get(3000,TimeUnit.MILLISECONDS);
			State state = (State) stateWrapper.get(0);

			state.validate();
			AVector<AccountStatus> accountList = state.getAccounts();
			BlobMap<AccountKey, PeerStatus> peerList = state.getPeers();

			mainParent.output.setField("State hash", stateHash.toString());
			mainParent.output.setField("Timestamp",state.getTimeStamp().toString());
			mainParent.output.setField("Timestamp value", Text.dateFormat(state.getTimeStamp().longValue()));
			mainParent.output.setField("Global Fees", Text.toFriendlyBalance(state.getGlobalFees().longValue()));
			mainParent.output.setField("Juice Price", Text.toFriendlyBalance(state.getJuicePrice().longValue()));
			mainParent.output.setField("Total Funds", Text.toFriendlyBalance(state.computeTotalFunds()));
			mainParent.output.setField("Number of accounts", accountList.size());
			mainParent.output.setField("Number of peers", peerList.size());
		} catch (Throwable t) {
			mainParent.showError(t);
		}
	}

}
