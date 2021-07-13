package convex.cli;

import java.util.List;
import java.util.logging.Logger;

import convex.cli.peer.PeerManager;
import convex.core.crypto.AKeyPair;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/*
 * 		local start command
 *
 *		convex.local.start
 *
 */

@Command(name="start",
	aliases={"st"},
	mixinStandardHelpOptions=true,
	description="Starts a local convex test network.")
public class LocalStart implements Runnable {

	private static final Logger log = Logger.getLogger(LocalStart.class.getName());

	@ParentCommand
	private Local localParent;

	@Option(names={"--count"},
		defaultValue = "" + Constants.LOCAL_START_PEER_COUNT,
		description="Number of local peers to start. Default: ${DEFAULT-VALUE}")
	private int count;

	@Override
	public void run() {
		Main mainParent = localParent.mainParent;
		PeerManager peerManager = PeerManager.create(mainParent.getSessionFilename());

		List<AKeyPair> keyPairList = mainParent.generateKeyPairs(count);

		log.info("Starting local network with "+count+" peer(s)");
		peerManager.launchLocalPeers(keyPairList);
		peerManager.showPeerEvents();
	}
}
