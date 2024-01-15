package convex.cli.local;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.cli.CLIError;
import convex.cli.Constants;
import convex.cli.Helpers;
import convex.cli.Main;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;
import convex.core.init.Init;
import convex.peer.API;
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
public class LocalStart implements Runnable {

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
		description="List of ports to assign to peers in the cluster. If not specified, will attempt to find available ports."
			+ "or a single --ports=8081,8082,8083 or --ports=8080-8090")
	private String[] ports;

	@Option(names={"--api-port"},
		defaultValue = "8080",
		description="REST API port, if set enable REST API to a peer in the local cluster")
	private int apiPort;

    /**
     * Gets n public keys for local test cluster
     * @param n Number of public keys
     * @return List of distinct public keys
     */
    private List<AKeyPair> getPublicKeys(int n) {
    	HashSet<AKeyPair> keyPairList = new HashSet<AKeyPair>();
    	
    	Main mainParent = localParent.cli();
      
		// load in the list of public keys to use as peers
		if (keystorePublicKey.length > 0) {
			List<String> values = Helpers.splitArrayParameter(keystorePublicKey);
			
			for (int index = 0; index < values.size(); index ++) {
				String keyPrefix = values.get(index);

				AKeyPair keyPair = mainParent.loadKeyFromStore(keyPrefix);
				if (keyPair == null) throw new CLIError("Unable to find public key in store: "+keyPrefix);
				keyPairList.add(keyPair);
			}
		}
		int left=n-keyPairList.size();
		if (left>0) {
			log.warn("Insufficient key pairs specified. Additional keypairs will be generated");
		
			List<AKeyPair> kp=mainParent.generateKeyPairs(left,mainParent.getKeyPassword());
			keyPairList.addAll(kp);
			mainParent.saveKeyStore();
		}
		if (keyPairList.size()<n) {
			throw new CLIError("Unable to generate sufficient keypairs!");
		}
		
		return new ArrayList<AKeyPair>(keyPairList);
    }
    
	@Override
	public void run() {
		List<AKeyPair> keyPairList = getPublicKeys(count);

		int peerPorts[] = null;
		if (ports != null) {
			try {
				peerPorts = Helpers.getPortList(ports, count);
			} catch (NumberFormatException e) {
				log.warn("cannot convert port number " + e);
				return;
			}
			if (peerPorts.length < count) {
				log.warn("Only {} ports specified for {} peers", peerPorts.length, count);
				return;
			}
		}
		log.info("Starting local network with "+count+" peer(s)");
		List<Server> servers=launchLocalPeers(keyPairList, peerPorts);
		int n=servers.size();
		log.info("Started: "+ n+" local peer"+((n>1)?"s":"")+" launched");
		if (apiPort > 0) {
			log.info("Starting REST api on port "+apiPort);
			
		}
		launchRestAPI(servers.get(0));
	}
	
	public List<Server> launchLocalPeers(List<AKeyPair> keyPairList, int peerPorts[]) {
		List<AccountKey> keyList=keyPairList.stream().map(kp->kp.getAccountKey()).collect(Collectors.toList());

		State genesisState=Init.createState(keyList);
		return API.launchLocalPeers(keyPairList,genesisState, peerPorts);
	}
	
	public void launchRestAPI(Server server) {
		RESTServer restServer=RESTServer.create(server);
		restServer.start();
	}
}
