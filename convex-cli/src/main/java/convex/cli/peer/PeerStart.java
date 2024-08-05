package convex.cli.peer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.cli.CLIError;
import convex.cli.mixins.RemotePeerMixin;
import convex.core.crypto.AKeyPair;
import convex.core.exceptions.TODOException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
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

	@Option(names = {"--reset" }, 
			description = "Reset and delete the etch database if it exists. Default: ${DEFAULT-VALUE}")
	private boolean isReset;

	@Option(names = { "--peer-port" }, 
			defaultValue = "18888",
			description = "Port number for the peer. Default is ${DEFAULT-VALUE}. If set to 0, will choose a random port.")
	private int port = 0;

	@Option(names = { "--url" }, description = "URL for the peer to publish. If not provided, the peer will have no public URL.")
	private String url;

//	@Option(names = { "-b",
//			"--bind-address" }, description = "Bind address of the network interface. Defaults to local loop back device for %n"
//                    + "local peer, and if a public --url is set, then defaults to all network devices.")
//	private String bindAddress;

	@Mixin
	protected RemotePeerMixin peerMixin;

	@Option(names = { "-a", "--address" }, description = "Account address to use for the peer controller.")
	private long addressNumber;
	


	@Override
	public void run() {
		storeMixin.loadKeyStore();
		AKeyPair peerKey=ensurePeerKey();
		
		log.debug("Preparing to start peer: "+peerKey.getAccountKey());
		
		try {

			throw new TODOException();
			// peerManager = PeerManager.create(mainParent.getSessionFilename(), keyPair, peerAddress, store);
			// peerManager.launchPeer(port, remotePeerHostname, url, bindAddress);
			// peerManager.showPeerEvents();
		} catch (Exception t) {
			throw new CLIError("Error starting peer: "+t.getMessage(),t);
		}
	}

	
}
