package convex.cli;

import java.io.File;
import java.io.IOException;
import java.util.List;

import convex.cli.peer.PeerManager;
import convex.cli.peer.SessionItem;
import convex.core.Belief;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.Address;
import convex.core.data.Hash;
import convex.core.data.SignedData;
import convex.core.store.AStore;
import convex.core.store.Stores;
import etch.EtchStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 * peer start command
 *
 * convex.peer.start
 *
 */

@Command(name = "start", aliases = { "st" }, mixinStandardHelpOptions = true, description = "Starts a local peer.")
public class PeerStart implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(PeerStart.class);

	@ParentCommand
	private Peer peerParent;

	@Spec
	CommandSpec spec;

	@Option(names={"-i", "--index-key"},
		defaultValue="0",
		description="Keystore index of the public/private key to use for the peer.")

    private int keystoreIndex;

	@Option(names = {
			"--public-key" }, defaultValue = "", description = "Hex string of the public key in the Keystore to use for the peer.%n"
					+ "You only need to enter in the first distinct hex values of the public key.%n"
					+ "For example: 0xf0234 or f0234")
	private String keystorePublicKey;

	@Option(names = { "-r",
			"--reset" }, description = "Reset and delete the etch database if it exists. Default: ${DEFAULT-VALUE}")
	private boolean isReset;

	@Option(names = { "--port" }, description = "Port number of this local peer.")
	private int port = 0;

	@Option(names = {
			"--host" }, defaultValue=Constants.HOSTNAME_PEER, description = "Hostname of this peer. Default: ${DEFAULT-VALUE}")
	private String hostname = Constants.HOSTNAME_PEER;

	@Option(names = {
			"--peer" }, description = "Hostname and port number of remote peer. If not provided then try to connect to a local peer")
	private String remotePeerHostname;

	@Option(names = { "-a", "--address" }, description = "Account address to use for the peer.")
	private long addressNumber;

	@Override
	public void run() {

		Main mainParent = peerParent.mainParent;
		PeerManager peerManager = null;

		AKeyPair keyPair = null;
		try {
			keyPair = mainParent.loadKeyFromStore(keystorePublicKey, keystoreIndex);
		} catch (Error e) {
			mainParent.showError(e);
			return;
		}

		if (keyPair == null) {
			log.warn("cannot load a valid key pair to perform peer start");
			return;
		}

		if (port != 0) {
			port = Math.abs(port);
		}
		if ( addressNumber == 0) {
			log.warn("please provide an account address to run the peer from.");
			return;
		}
		Address peerAddress = Address.create(addressNumber);

		if (remotePeerHostname == null) {
			try {
				SessionItem item = Helpers.getSessionItem(mainParent.getSessionFilename());
				if (item != null) {
					remotePeerHostname = item.getHostname();
				}
				else {
					log.warn("Cannot find a local peer to connect too");
					return;
				}
			} catch (IOException e) {
				log.warn("Cannot load the session control file");
				return;
			}
		}
		else {
			remotePeerHostname = remotePeerHostname.strip();
		}
		if (hostname == null) {
			log.warn("you need to provide a host name for this peer");
			return;
		}
		hostname = hostname.strip();

		try {
			AStore store = null;
			String etchStoreFilename = mainParent.getEtchStoreFilename();
			if (etchStoreFilename != null && !etchStoreFilename.isEmpty()) {
				File etchFile = new File(etchStoreFilename);
				if (isReset && etchFile.exists()) {
					log.info("reset: removing old etch storage file {}", etchStoreFilename);
					etchFile.delete();
				}
				store = EtchStore.create(etchFile);
			} else {
				store = Stores.getDefaultStore();
			}
			peerManager = PeerManager.create(mainParent.getSessionFilename(), keyPair, peerAddress, store);
			List<Hash> networkHashList = peerManager.getNetworkHashList(remotePeerHostname);
			log.info("will join remote networkId " + networkHashList.get(2).toHexString());

			State baseState = peerManager.aquireState(remotePeerHostname, networkHashList.get(2));
			SignedData<Belief> signedBelief = peerManager.aquireBelief(remotePeerHostname, networkHashList.get(0));
			peerManager.launchPeer(hostname, port, remotePeerHostname, baseState, signedBelief);
			peerManager.showPeerEvents();
		} catch (Throwable t) {
			mainParent.showError(t);
		}
	}
}
