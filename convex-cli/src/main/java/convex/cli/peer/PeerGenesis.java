package convex.cli.peer;

import java.util.HashMap;
import java.util.List;

import convex.cli.CLIError;
import convex.cli.Main;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.init.Init;
import convex.peer.API;
import convex.peer.Server;
import etch.EtchStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 * Peer genesis command
 */
@Command(
	name = "genesis",
	mixinStandardHelpOptions = true, 
	description = "Instantiate a Convex network.")
public class PeerGenesis extends APeerCommand {


	@ParentCommand
	private Peer peerParent;

	
	@Spec
	CommandSpec spec;

	@Option(names = {
			"--public-key" }, description = "Hex string of the public key in the Keystore to use for the peer.%n"
					+ "You only need to enter in the first distinct hex values of the public key.%n"
					+ "For example: 0xf0234 or f0234")
	private String keystorePublicKey;

	@Override
	public void run() {

		Main mainParent = peerParent.mainParent;

		AKeyPair keyPair = null;
		if (keystorePublicKey!=null) {
			keyPair = mainParent.loadKeyFromStore(keystorePublicKey);
			if (keyPair == null) {
				throw new CLIError("Cannot load specified key pair to perform peer start: "+keystorePublicKey);
			}
		} else {
			keyPair=AKeyPair.generate();
			mainParent.addKeyPairToStore(keyPair,mainParent.getKeyPassword());
			cli().printErr("Generated new Keypair with public key: "+keyPair.getAccountKey());
		}

		EtchStore store=getEtchStore();
		
		State genesisState=Init.createState(List.of(keyPair.getAccountKey()));
		
		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.STORE, store);
		config.put(Keywords.STATE, genesisState);
		config.put(Keywords.KEYPAIR, keyPair);
		Server s=API.launchPeer(config);
		s.close();
	}
}
