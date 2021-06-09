package convex.cli;

import java.io.File;
import java.io.IOException;
import java.util.logging.Logger;

import convex.cli.peer.PeerManager;
import convex.core.crypto.AKeyPair;
import convex.core.data.Address;
import convex.core.store.AStore;
import convex.peer.Server;
import etch.EtchStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 *  peer start command
 *
 *		convex.peer.start
 *
 */

@Command(name="start",
	aliases={"st"},
	mixinStandardHelpOptions=true,
	description="Starts a local peer.")
public class PeerStart implements Runnable {

	private static final Logger log = Logger.getLogger(PeerStart.class.getName());

	@ParentCommand
	private Peer peerParent;

	@Spec CommandSpec spec;

	@Option(names={"-i", "--index"},
		defaultValue="-1",
		description="Keystore index of the public/private key to use for the peer.")
	private int keystoreIndex;

	@Option(names={"--public-key"},
		defaultValue="",
		description="Hex string of the public key in the Keystore to use for the peer.%n"
			+ "You only need to enter in the first distinct hex values of the public key.%n"
			+ "For example: 0xf0234 or f0234")
	private String keystorePublicKey;

	@Option(names={"-r", "--reset"},
		description="Reset and delete the etch database if it exists. Default: ${DEFAULT-VALUE}")
	private boolean isReset;

	@Option(names={"--port"},
		description="Port number to connect or create a peer.")
	private int port = 0;

	@Option(names={"--host"},
		defaultValue=Constants.HOSTNAME_PEER,
		description="Hostname to connect to a peer. Default: ${DEFAULT-VALUE}")
	private String hostname;

	@Option(names={"-a", "--address"},
	description="Account address to use for the peer.")
	private long addressNumber;

	@Override
	public void run() {

		Main mainParent = peerParent.mainParent;
		PeerManager peerManager = PeerManager.create(mainParent.getSessionFilename());

		int port = 0;
		AKeyPair keyPair = null;
		try {
			keyPair = mainParent.loadKeyFromStore(keystorePublicKey, keystoreIndex);
		} catch (Error e) {
			log.info(e.getMessage());
			return;
		}

		if (port!=0) {
			port = Math.abs(port);
		}
		if ( addressNumber == 0) {
			log.warning("please provide an account address to run the peer from.");
			return;
		}
		Address peerAddress = Address.create(addressNumber);

		if (hostname == null) {
			try {
				hostname = Helpers.getSessionHostname(mainParent.getSessionFilename());
			} catch (IOException e) {
				log.warning("Cannot load the session control file");
			}
		}
		if (hostname == null) {
			log.warning("Cannot find a local peer running, start the local network, or use the --hostname option");
			return;
		}

		try {
			AStore store = null;
			String etchStoreFilename = mainParent.getEtchStoreFilename();
			if (etchStoreFilename != null && !etchStoreFilename.isEmpty()) {
				File etchFile = new File(etchStoreFilename);
				if ( isReset && etchFile.exists()) {
					log.info("reset: removing old etch storage file " + etchStoreFilename);
					etchFile.delete();
				}
				store = EtchStore.create(etchFile);
			}
			peerManager.launchPeer(keyPair, peerAddress, hostname, port, store);
			peerManager.showPeerEvents();
		} catch (Throwable t) {
			System.out.println("Unable to launch peer "+t);
		}
	}
}
