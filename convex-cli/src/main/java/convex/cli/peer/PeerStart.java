package convex.cli.peer;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.cli.CLIError;
import convex.cli.ExitCodes;
import convex.cli.mixins.RemotePeerMixin;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.State;
import convex.core.data.AccountKey;
import convex.core.cvm.Address;
import convex.core.data.Blob;
import convex.core.data.Keyword;
import convex.core.cvm.Keywords;
import convex.core.init.Init;
import convex.etch.EtchStore;
import convex.peer.API;
import convex.peer.ConfigException;
import convex.peer.LaunchException;
import convex.peer.Server;
import convex.restapi.RESTServer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 * Start a Convex peer
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

	@Option(names = { "--url" }, 
			description = "URL for the peer to publish. If not provided, the peer will have no public URL.")
	private String url;
	
	@Option(names = { "--norest" }, description = "Disable REST srever.")
	private boolean norest;
	
	@Option(names = { "--genesis" }, 
			defaultValue = "${env:CONVEX_GENESIS_SEED}",
			description = "Governance seed for network genesis. For testing use only.")
	private String genesis;

	@Option(names = { "--api-port" }, 
			defaultValue = "8080",
			description = "Port for REST API.")
	private Integer apiport;

//	@Option(names = { "-b",
//			"--bind-address" }, description = "Bind address of the network interface. Defaults to local loop back device for %n"
//                    + "local peer, and if a public --url is set, then defaults to all network devices.")
//	private String bindAddress;

	@Mixin
	protected RemotePeerMixin peerMixin;

	@Option(names = { "-a", "--address" }, description = "Account address to use for the peer controller.")
	private String controllerAddress;
	
	private AKeyPair findPeerKey(EtchStore store) {
		// First check user supplied peer key. If we have it, use it
		AKeyPair kp=specifiedPeerKey();
		if (kp!=null) return kp;
		
		// if user specified a --peer-key, but it wasn't found in keystore
		String specifiedKey=peerKeyMixin.getPublicKey();
		if (specifiedKey!=null) {
			throw new CLIError(ExitCodes.CONFIG,"Peer key not found in Store: "+specifiedKey);
		}
		
		// In strict mode, we insist on a peer key
		paranoia("--peer-key not sepcified");
		
		log.debug("--peer-key not available, attempting to infer from store");
		try {
			List<AccountKey> peerList=API.listPeers(store);
			if (peerList.size()==0) {
				throw new CLIError(ExitCodes.CONFIG,"No peers configured in Etch store "+store+". Consider using `convex peer create` or `convex peer genesis` first.");
			} else if (peerList.size()>1) {
				throw new CLIError(ExitCodes.CONFIG,"Multiple peers configured in Etch store "+store+". specify which one you want with --peer-key.");
			}
			AccountKey peerKey=peerList.get(0);
			AKeyPair pkp=storeMixin.loadKeyFromStore(peerKey.toHexString(), ()->peerKeyMixin.getKeyPassword());
			return pkp;
		} catch (IOException e) {
			log.debug("IO Exception trying to read etch peer list",e);
		}
		
		return null;
	}

	@Override
	public void execute() throws InterruptedException {
		
		storeMixin.ensureKeyStore();
		try (EtchStore store = etchMixin.getEtchStore()) {
			AKeyPair peerKey;
			AKeyPair genesisKey=null;
			if (genesis!=null&&(!genesis.isEmpty())) {
				// Using a genesis seed for testing
				paranoia("Should't use Genesis Seed in strict security mode! Consider key compromised!");
				Blob seed=Blob.parse(genesis);
				if (seed==null) {
					throw new CLIError("Genesis seed must be 32 byte hex blob");
				}
				if (seed.count()!=32) {
					throw new CLIError("Genesis seed must be 32 byte hex blob");
				}
				peerKey = AKeyPair.create(seed);
				genesisKey=peerKey;
				informWarning("Using test genesis seed: "+seed);
			} else {
				//
				peerKey=findPeerKey(store);
				if (peerKey==null) {
					informWarning("No --peer-key specified or inferred from Etch Store "+store);
					showUsage();
					return;
				}
			}

			Address controller=Address.parse(controllerAddress);
			if (controller==null) {
				paranoia("--address for peer controller not specified");
				log.debug("Controller address not specified.");
			}

			RESTServer restServer=null;
			try {
				HashMap<Keyword,Object> config=new HashMap<>();
				config.put(Keywords.KEYPAIR, peerKey);
				config.put(Keywords.STORE, store);
				if (genesisKey!=null) {
					AccountKey gpk=genesisKey.getAccountKey();
					State state=Init.createState(gpk,gpk,List.of(gpk));
					informWarning("Greated genesis State: "+state.getHash());
					config.put(Keywords.STATE, state);
				}
				Server server=API.launchPeer(config);
				
				if (!norest) {
					restServer=RESTServer.create(server); 
					restServer.start(apiport);
				}
				
				informSuccess("Peer started");
				server.waitForShutdown();
			} catch (ConfigException t) {
				throw new CLIError(ExitCodes.CONFIG,"Error in peer configuration: "+t.getMessage(),t);
			} catch (LaunchException e) {
				throw new CLIError("Error launching peer: "+e.getMessage(),e);
			} finally {
				if (restServer!=null) restServer.close();
				inform("Peer shutdown completed");
			}
		}
	}


	
}
