package convex.cli.peer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.cli.CLIError;
import convex.cli.Main;
import convex.core.crypto.AKeyPair;
import convex.core.data.Address;
import convex.core.exceptions.TODOException;
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

@Command(
	name = "start",
	mixinStandardHelpOptions = true, 
	description = "Start a local peer.")
public class PeerStart extends APeerCommand {

	private static final Logger log = LoggerFactory.getLogger(PeerStart.class);

	@ParentCommand
	private Peer peerParent;

	@Spec
	CommandSpec spec;

	@Option(names = {
			"--public-key" }, description = "Hex string of the public key in the Keystore to use for the peer.%n"
					+ "You only need to enter in the first distinct hex values of the public key.%n"
					+ "For example: 0xf0234 or f0234")
	private String keystorePublicKey;

	@Option(names = { "-r",
			"--reset" }, description = "Reset and delete the etch database if it exists. Default: ${DEFAULT-VALUE}")
	private boolean isReset;

	@Option(names = { "--port" }, 
			defaultValue = "18888",
			description = "Port number for the peer. Default is ${DEFAULT-VALUE}. If set to 0, will choose a random port.")
	private int port = 0;

	@Option(names = { "--url" }, description = "URL for the peer to publish. If not provided, the peer will have no public URL.")
	private String url;

//	@Option(names = { "-b",
//			"--bind-address" }, description = "Bind address of the network interface. Defaults to local loop back device for %n"
//                    + "local peer, and if a public --url is set, then defaults to all network devices.")
//	private String bindAddress;

    @Option(names = {
			"--peer" }, description = "URL of remote peer. If not provided then try to connect to a local peer")
	private String remotePeerHostname;

	@Option(names = { "-a", "--address" }, description = "Account address to use for the peer controller.")
	private long addressNumber;

	@Override
	public void run() {

		Main mainParent = peerParent.mainParent;

		AKeyPair keyPair = null;
		if (keystorePublicKey!=null) {
			keyPair = mainParent.loadKeyFromStore(keystorePublicKey);
			if (keyPair == null) {
				log.warn("Cannot load specified key pair to perform peer start: "+keystorePublicKey);
				return;
			}
		} else {
			keyPair=AKeyPair.generate();
			mainParent.storeMixin.addKeyPairToStore(mainParent, keyPair,mainParent.getKeyPassword());
			log.warn("Generated new Keypair with public key: "+keyPair.getAccountKey());
		}

		if (port != 0) {
			port = Math.abs(port);
		}
		if ( addressNumber == 0) {
			log.warn("please provide an account address to run the peer from.");
			return;
		}
		Address peerAddress = Address.create(addressNumber);
		if (peerAddress==null) throw new CLIError("Invalid peer controller address: "+addressNumber);

		if (remotePeerHostname == null) {

		}
		else {
			remotePeerHostname = remotePeerHostname.strip();
		}

		try {

			throw new TODOException();
			// peerManager = PeerManager.create(mainParent.getSessionFilename(), keyPair, peerAddress, store);
			// peerManager.launchPeer(port, remotePeerHostname, url, bindAddress);
			// peerManager.showPeerEvents();
		} catch (Throwable t) {
			throw new CLIError("Error starting peer",t);
		}
	}
}
