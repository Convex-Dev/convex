package convex.cli.peer;

import java.util.HashMap;
import java.util.List;

import convex.cli.CLIError;
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

	@Option(names = {"--public-key" }, 
			description = "Hex string of the public key in the Keystore to use for the genesis peer.%n"
					+ "You only need to enter in the first distinct hex values of the public key.%n"
					+ "For example: 0xf0234 or f0234")
	private String genesisKey;

	@Override
	public void run() {

		AKeyPair keyPair = null;
		if (genesisKey!=null) {
			keyPair = cli().loadKeyFromStore(genesisKey);
			if (keyPair == null) {
				throw new CLIError("Cannot find specified key pair to perform peer start: "+genesisKey);
			}
		} else {
//			if (cli().prompt("No key pair specified. Continue by creating a new one? (Y/N)")) {
//				throw new CLIError("Unable to obtain genesis key pair, aborting");
//			}
			if (cli().isParanoid()) throw new CLIError("Aborting due to strict security: no key pair specified");
			keyPair=AKeyPair.generate();
			cli().storeMixin.addKeyPairToStore(cli(), keyPair,cli().getKeyPassword());
			cli().storeMixin.saveKeyStore();
			cli().inform("Generated new Keypair with public key: "+keyPair.getAccountKey());
		}

		EtchStore store=getEtchStore();
		
		State genesisState=Init.createState(List.of(keyPair.getAccountKey()));
		cli().inform("Created genersis state with hash: "+genesisState.getHash());
		
		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.STORE, store);
		config.put(Keywords.STATE, genesisState);
		config.put(Keywords.KEYPAIR, keyPair);
		Server s=API.launchPeer(config);
		s.close();
		cli().informSuccess("Convex genesis succeeded!");
	}
}
