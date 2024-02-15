package convex.peer;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Peer;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Lists;
import convex.core.init.Init;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.util.Utils;
import etch.EtchStore;


/**
 * Class providing a simple API to operate a peer protocol Server.
 *
 * Suitable for library usage, e.g. if a user application wants to
 * instantiate a local network peer.
 *
 * "If you don't believe it or don't get it , I don't have time to convince you"
 * - Satoshi Nakamoto
 */
public class API {

	protected static final Logger log = LoggerFactory.getLogger(API.class.getName());

	/**
	 * <p>Launches a Peer Server with a supplied configuration.</p>
	 *
	 * <p>Config keys are:</p>
	 *
	 * <ul>
	 * <li>:keypair (required, AKeyPair) - AKeyPair instance.
	 * <li>:port (optional, Integer) - Integer port number to use for incoming connections. Zero causes random allocation (also the default).
	 * <li>:store (optional, AStore) - AStore instance. Defaults to the configured global store
	 * <li>:source (optional, String) - URL for Peer to replicate initial State/Belief from.
	 * <li>:state (optional, State) - Genesis state. Defaults to a fresh genesis state for the Peer if neither :source nor :state is specified
	 * <li>:restore (optional, Boolean) - Boolean Flag to restore from existing store. Default to true
	 * <li>:persist (optional, Boolean) - Boolean flag to determine if peer state should be persisted in store at server close. Default true.
	 * <li>:url (optional, String) - public URL for server. If provided, peer will set its public on-chain address based on this, and the bind-address to 0.0.0.0.
	 * <li>:auto-manage (optional Boolean) - set to true for peer to auto-manage own account. Defaults to true.
     * <li>:bind-address (optional String) - IP address of the ethernet device to bind too. For public peers set too 0.0.0.0. Default to 127.0.0.1.
	 * </ul>
	 *
	 * @param peerConfig Config map for the new Peer
     *
	 * @return New Server instance
	 */
	public static Server launchPeer(Map<Keyword, Object> peerConfig) {
		HashMap<Keyword,Object> config=new HashMap<>(peerConfig);

		// State not strictly necessary? Should be possible to restore a Peer from store
		if (!(config.containsKey(Keywords.STATE)
				||config.containsKey(Keywords.STORE)
				||config.containsKey(Keywords.SOURCE)
				)) {
			throw new IllegalArgumentException("Peer launch requires a genesis :state, remote :source or existing :store in config");
		}

		if (!config.containsKey(Keywords.KEYPAIR)) throw new IllegalArgumentException("Peer launch requires a "+Keywords.KEYPAIR+" in config");

		AStore tempStore=Stores.current();
		try {
			// Port defaults to null, which uses default port if available or picks a random port 
			if (!config.containsKey(Keywords.PORT)) {
				config.put(Keywords.PORT, null);
			}
			
			// Configure the store and use on this thread
			AStore store;
			if (config.containsKey(Keywords.STORE)) {
				store=(AStore)config.get(Keywords.STORE);
			} else {
				store=EtchStore.createTemp("defaultPeerStore");
				//config.put(Keywords.STORE, tempStore);
				config.put(Keywords.STORE, store);
			}
			Stores.setCurrent(store);
			
			if (!config.containsKey(Keywords.RESTORE)) config.put(Keywords.RESTORE, true);
			if (!config.containsKey(Keywords.PERSIST)) config.put(Keywords.PERSIST, true);
			if (!config.containsKey(Keywords.AUTO_MANAGE)) config.put(Keywords.AUTO_MANAGE, true);

			// if URL is set and it is not a local address and no BIND_ADDRESS is set, then the default for BIND_ADDRESS will be 0.0.0.0
			if (config.containsKey(Keywords.URL) && !config.containsKey(Keywords.BIND_ADDRESS)) {
				InetAddress ip = InetAddress.getByName((String) config.get(Keywords.URL));
				if (! (ip.isAnyLocalAddress() || ip.isLoopbackAddress()) ) {
					config.put(Keywords.BIND_ADDRESS, "0.0.0.0");
				}
			}
			Server server = Server.create(config);
			server.launch();
			return server;
		} catch (Throwable t) {
			throw Utils.sneakyThrow(t);
		} finally {
			Stores.setCurrent(tempStore);
		}
	}
	
	/**
	 * Launches a peer with a default configuration. Mainly for testing.
	 * @return Newly launched Server instance
	 */
	public static Server launchPeer() {
		AKeyPair kp=AKeyPair.generate();
		State genesis=Init.createState(Lists.of(kp.getAccountKey()));
		HashMap<Keyword, Object> config=new HashMap<>();
		config.put(Keywords.KEYPAIR, kp);
		config.put(Keywords.STATE, genesis);
		return launchPeer(config);
	}

	/**
	 * Launch a local set of peers. Intended mainly for testing / development.
	 *
	 * The Peers will have a unique genesis State, i.e. an independent network
	 *
	 * @param keyPairs List of keypairs for peers
	 * @param genesisState genesis state for local network
	 *
	 * @return List of Servers launched
	 *
	 */
	public static List<Server> launchLocalPeers(List<AKeyPair> keyPairs, State genesisState) {
		return launchLocalPeers(keyPairs, genesisState, null);
	}
	/**
	 * Launch a local set of peers. Intended mainly for testing / development.
	 *
	 * The Peers will have a unique genesis State, i.e. an independent network
	 *
	 * @param keyPairs List of keypairs for peers
	 * @param genesisState Genesis state for local network
	 * @param peerPorts Array of ports to use for each peer, if == null then randomly assign port numbers
	 *
	 * @return List of Servers launched
	 *
	 */
	public static List<Server> launchLocalPeers(List<AKeyPair> keyPairs, State genesisState, int peerPorts[]) {
		int count=keyPairs.size();

		List<Server> serverList = new ArrayList<Server>();

		Map<Keyword, Object> config = new HashMap<>();

		// Peer should get a new allocated port
		config.put(Keywords.PORT, null);

		// Peers should all have the same genesis state
		config.put(Keywords.STATE, genesisState);
		
		// Test code to share a store
		// config.put(Keywords.STORE, Stores.current());

		// Automatically manage Peer connections
		config.put(Keywords.AUTO_MANAGE, true);

		for (int i = 0; i < count; i++) {
			AKeyPair keyPair = keyPairs.get(i);
			config.put(Keywords.KEYPAIR, keyPair);
			if (peerPorts != null) {
				if	(peerPorts.length>i) {
					config.put(Keywords.PORT, peerPorts[i]);
				} else {
					// default to zero (random port) 
					config.put(Keywords.PORT, 0);
				}
			}
			Server server = API.launchPeer(config);
			serverList.add(server);
		}

		Server genesisServer = serverList.get(0);

		// go through 1..count-1 peers and join them all to the genesis Peer
		// do this twice to allow for all of the peers to get all of the address in the group of peers

		genesisServer.setHostname("localhost:"+genesisServer.getPort());

		for (int i = 1; i < count; i++) {
			Server server=serverList.get(i);

			// Join each additional Server to the Peer #0
			ConnectionManager cm=server.getConnectionManager();
			cm.connectToPeer(genesisServer.getHostAddress());

			// Join server #0 to this server
			genesisServer.getConnectionManager().connectToPeer(server.getHostAddress());
			server.setHostname("localhost:"+server.getPort());
		}

		// wait for the peers to sync upto 10 seconds
		//API.waitForNetworkReady(serverList, 10);
		return serverList;
	}

	/**
	 * Returns a true value if the local network is ready and synced with the same consensus state hash.
	 *
	 * @param serverList List of local peer servers running on the local network.
	 *
	 * @param timeoutMillis Number of milliseconds to wait before exiting with a failure.
	 *
	 * @return Return true if all server peers have the same consensus hash, else false is a timeout.
	 *
	 */
	public static boolean isNetworkReady(List<Server> serverList, long timeoutMillis) {
		boolean isReady = false;
		long timeoutTime = Utils.getTimeMillis() + timeoutMillis;
		while (timeoutTime > Utils.getTimeMillis()) {
			isReady = true;
			Hash consensusHash = null;
			for (Server server: serverList) {
				Peer peer = server.getPeer();
				if (consensusHash == null) {
					consensusHash = peer.getConsensusState().getHash();
				}
				if (!consensusHash.equals(peer.getConsensusState().getHash())) {
					isReady=false;
				}
			}
			if (isReady) {
				break;
			}
			try {
				Thread.sleep(100);
			} catch ( InterruptedException e) {
				return false;
			}
		}
		return isReady;
    }


}
