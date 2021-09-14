package convex.cli;

import java.lang.NumberFormatException;
import java.util.ArrayList;
import java.util.List;

import convex.cli.peer.PeerManager;
import convex.core.crypto.AKeyPair;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

	private static final Logger log = LoggerFactory.getLogger(LocalStart.class);

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

    @Option(names={"--ports"},
		description="Range or list of ports to assign each peer in the cluster. This can be a multiple of --ports %n"
			+ "or a single --ports=8081,8082,8083 or --ports=8080-8090")
	private String[] ports;

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
					mainParent.showError(e);
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
						mainParent.showError(e);
						return;
					}
				}
			}
		}

		if (keyPairList.size() == 0) {
			keyPairList = mainParent.generateKeyPairs(count);
		}

		if (count > keyPairList.size()) {
			log.error(
				"Not enougth public keys provided. " +
				"You have requested {} peers to start, but only provided {} public keys",
				count,
				keyPairList.size()
			);
		}
		int peerPorts[] = null;
		if (ports != null) {
			try {
				peerPorts = mainParent.getPortList(ports, count);
			} catch (NumberFormatException e) {
				log.warn("cannot convert port number " + e);
				return;
			}
			if (peerPorts.length < count) {
				log.warn("you need only provided {} ports you need to provide at least {} ports", peerPorts.length, count);
				return;
			}
		}
		try {

			peerManager.startPeerEvents();
			log.info("Starting local network with "+count+" peer(s)");
			peerManager.launchLocalPeers(keyPairList, peerPorts);
			log.info("Local Peers launched");
			while (true) {
				Thread.sleep(1000);
			}
		} catch (Throwable t) {
			mainParent.showError(t);
		}
	}
}
