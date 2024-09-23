package convex.cli.peer;

import java.util.HashMap;
import java.util.List;

import convex.cli.CLIError;
import convex.cli.ExitCodes;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.init.Init;
import convex.etch.EtchStore;
import convex.peer.API;
import convex.peer.PeerException;
import convex.peer.Server;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.ScopeType;

/**
 * Peer genesis command
 * 
 * Creates a genesis state and peer in the specified local store, ready for launch
 */
@Command(
	name = "genesis",
	mixinStandardHelpOptions = true, 
	description = "Instantiate a Convex network.")
public class PeerGenesis extends APeerCommand {

	@ParentCommand
	private Peer peerParent;
	
	@Option(names = { "--governance-key" }, 
			defaultValue = "${env:CONVEX_GOVERNANCE_KEY}", 
			scope = ScopeType.INHERIT, 
			description = "Network Governance Key. Must be a valid Ed25519 public key. Genesis key will be used if not specified (unless security is strict).")
	protected String governanceKey;

	@Override
	public void execute() throws InterruptedException {
		storeMixin.ensureKeyStore();

		// Key for controller. Used for genesis / governance in non-strict mode
		// Otherwise peer key can be used
		AKeyPair genesisKey = ensureControllerKey();
		if (genesisKey==null) {
			informWarning("You must specify at least a genesis --key");
			showUsage();
			return;
		}
		
		EtchStore etch=etchMixin.getEtchStore();
		try {

			// Key for initial peer. Needed for genesis start
			AKeyPair peerKey = specifiedPeerKey();
			if (peerKey==null) {
				paranoia("--peer-key must be specified in strict mode");
				peerKey=genesisKey;
				inform("Using genesis key for first peer: "+genesisKey.getAccountKey());
			}
			
			AccountKey govKey=AccountKey.parse(governanceKey);
			if (govKey==null) {
				paranoia("--governance-key must be specified in strict security mode");
				if (governanceKey==null) {
					inform("Using genesis key for governance: "+genesisKey.getAccountKey());
					govKey=genesisKey.getAccountKey();
				} else {
					throw new CLIError(ExitCodes.DATAERR,"Unable to parse --governance-key argument. Should be a 32-byte hex key.");
				}
			}
	
			EtchStore store=getEtchStore();
			
			State genesisState=Init.createState(govKey,genesisKey.getAccountKey(),List.of(peerKey.getAccountKey()));
			inform("Created genesis state with hash: "+genesisState.getHash());
			
			inform("Testing genesis state peer initialisation");
			
			HashMap<Keyword,Object> config=new HashMap<>();
			config.put(Keywords.STORE, store);
			config.put(Keywords.STATE, genesisState);
			config.put(Keywords.KEYPAIR, peerKey);
			Server s=API.launchPeer(config); 
			s.close();
			informSuccess("Convex genesis succeeded!");
		}  catch (PeerException e) {
			throw new CLIError("Peer genesis failed: "+e.getMessage(),e);
		} finally {
			etch.close();
		}
	}
}
