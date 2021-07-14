package convex.cli;

import java.util.ArrayList;
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

	@Option(names={"-i", "--index-key"},
		defaultValue="0",
		description="One or more keystore index of the public/private key to use to run a peer.")
	private String[] keystoreIndex;

	@Option(names={"--public-key"},
		defaultValue="",
		description="One or more hex string of the public key in the Keystore to use to run a peer.%n"
			+ "You only need to enter in the first distinct hex values of the public key.%n"
			+ "For example: 0xf0234 or f0234")
	private String[] keystorePublicKey;

	@Override
	public void run() {
		Main mainParent = localParent.mainParent;
		PeerManager peerManager = PeerManager.create(mainParent.getSessionFilename());

		List<AKeyPair> keyPairList = new ArrayList<AKeyPair>();

		// load in the list of public keys to use as peers
		if (keystorePublicKey.length > 0) {
			List<String> values = Helpers.splitArrayParameter(keystorePublicKey);
			for (int index = 0; index < values.size(); index ++) {
				String publicKeyText = values.get(index);
				try {
					AKeyPair keyPair = mainParent.loadKeyFromStore(publicKeyText, 0);
					if (keyPair != null) {
						keyPairList.add(keyPair);
					}
				} catch (Error e) {
					log.severe(e.getMessage());
					return;
				}
			}
		}

		// load in a list of key indexes to use as peers
		if (keystoreIndex.length > 0) {
			List<String> values = Helpers.splitArrayParameter(keystoreIndex);
			for (int index = 0; index < values.size(); index ++) {
				int indexKey = Integer.parseInt(values.get(index));
				if (indexKey > 0) {
					try {
						AKeyPair keyPair = mainParent.loadKeyFromStore("", indexKey);
						if (keyPair != null) {
							keyPairList.add(keyPair);
						}
					} catch (Error e) {
						log.severe(e.getMessage());
						return;
					}
				}
			}
		}

		if (keyPairList.size() == 0) {
			keyPairList = mainParent.generateKeyPairs(count);
		}

		if (count > keyPairList.size()) {
			log.severe(
				String.format("Not enougth public keys provided. " +
				"You have requested %d peers to start, but only provided %d public keys",
				count,
				keyPairList.size()
				)
			);
		}
		log.info("Starting local network with "+count+" peer(s)");
		peerManager.launchLocalPeers(keyPairList);
		peerManager.showPeerEvents();
	}
}
