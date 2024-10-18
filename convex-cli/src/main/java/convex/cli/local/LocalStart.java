package convex.cli.local;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.cli.CLIError;
import convex.cli.Constants;
import convex.cli.ExitCodes;
import convex.cli.Helpers;
import convex.core.cvm.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;
import convex.core.init.Init;
import convex.peer.API;
import convex.peer.PeerException;
import convex.peer.Server;
import convex.restapi.RESTServer;
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
	mixinStandardHelpOptions=true,
	description="Starts a temporary local convex test network. Useful for development and testing purposes.")
public class LocalStart extends ALocalCommand {

	private static final Logger log = LoggerFactory.getLogger(LocalStart.class);

	@ParentCommand
	private Local localParent;

	@Option(names={"--count"},
		defaultValue = "" + Constants.LOCAL_START_PEER_COUNT,
		description="Number of local peers to start. Default: ${DEFAULT-VALUE}")
	private int count;

	@Option(names={"--public-key"},
		defaultValue="",
		description="One or more hex string of the public key in the Keystore to use to run a peer.%n"
			+ "You only need to enter in the first distinct hex values of the public key.%n"
			+ "For example: 0xf0234 or f0234")
	private String[] keystorePublicKey;

  @Option(names={"--ports"},
		description="List of ports to assign to peers. If not specified, will attempt to find available ports."
			+ "e.g. --ports=8081,8082,8083 ")
	private String[] ports;

	@Option(names={"--api-port"},
		defaultValue = "8080",
		description="REST API port, enables REST API to the first peer in the local cluster. Default: ${DEFAULT-VALUE}")
	private int apiPort;

    /**
     * Gets n public keys for local test cluster
     * @param count Number of public keys
     * @return List of distinct public keys
     */
    private List<AKeyPair> getPeerKeyPairs(int count) {
    	ArrayList<AKeyPair> keyPairList = new ArrayList<AKeyPair>();
      
		// load in the list of public keys to use as peers
		if (keystorePublicKey.length > 0) {
			List<String> values = Helpers.splitArrayParameter(keystorePublicKey);
			
			for (int index = 0; index < values.size(); index ++) {
				String keyPrefix = values.get(index);
				if (keyPrefix.isBlank()) continue;

				AKeyPair keyPair = storeMixin.loadKeyFromStore(keyPrefix, ()->keyMixin.getKeyPassword());
				if (keyPair == null) {
					log.warn("Unable to find public key in store: "+keyPrefix);
				} else {
					keyPairList.add(keyPair);
				}
			}
		}
		int left=count-keyPairList.size();
		if (left>0) {
			informWarning("Insufficient key pairs specified. Additional "+left+" keypair(s) will be generated");
			for (int i=0; i<left; i++) {
				AKeyPair kp=AKeyPair.generate();
				keyPairList.add(kp);
				log.info("Generated key: "+kp.getAccountKey().toChecksumHex()+" Priv: "+kp.getSeed());
			}
		}
		
		if (new HashSet<>(keyPairList).size()<keyPairList.size()) {
			throw new CLIError("Duplicate peer keys provided!");
		}
		
		return new ArrayList<AKeyPair>(keyPairList);
    }
    
    /**
     * Gets array of ports to assign to peers
     * @return
     */
	private int[] getPeerPorts() {
		int peerPorts[]=null;
		if (ports != null) {
			peerPorts = Helpers.getPortList(ports, count);
			if (peerPorts==null) throw new CLIError(ExitCodes.DATAERR,"Failed to parse port list");
			if (peerPorts.length < count) {
				log.debug("Only {} ports specified for {} peers", peerPorts.length, count);
			}
		}
		return peerPorts;
	}
    
	@Override
	public void execute() throws InterruptedException{
		List<AKeyPair> keyPairList = getPeerKeyPairs(count);
		int peerPorts[] = getPeerPorts();
		
		inform("Starting local test network with "+count+" peer(s)");
		List<Server> servers=launchLocalPeers(keyPairList, peerPorts);
		int n=servers.size();
		

		if (apiPort > 0) {
			log.debug("Requesting REST API on port "+apiPort);
		}
		launchRestAPI(servers.get(0));
		
		// informWarning("Failed to start REST server: "+t);
		
		informSuccess("Started: "+ n+" local peer"+((n>1)?"s":"")+" launched");
		servers.get(0).waitForShutdown();
		informWarning("Peer shutdown complete");
	}

	public List<Server> launchLocalPeers(List<AKeyPair> keyPairList, int peerPorts[]) throws InterruptedException {
		List<AccountKey> keyList=keyPairList.stream().map(kp->kp.getAccountKey()).collect(Collectors.toList());

		State genesisState=Init.createState(keyList);
		try {
			return API.launchLocalPeers(keyPairList,genesisState, peerPorts);
		} catch (PeerException e) {
			throw new CLIError(ExitCodes.CONFIG,"Failed to launch peer(s) : "+e.getMessage(),e);
		} 
	}
	
	public RESTServer launchRestAPI(Server server) {
		RESTServer restServer=RESTServer.create(server);
		restServer.start();
		return restServer;
	}
}
