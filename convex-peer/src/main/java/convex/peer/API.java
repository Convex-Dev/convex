package convex.peer;

import java.lang.InterruptedException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Peer;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.util.Utils;
import convex.net.Connection;


/**
 * Class providing a simple API to a peer Server.
 *
 * Suitable for library usage, e.g. if a usr application wants to
 * instantiate a local network peer.
 *
 * "If you don't believe it or don't get it , I don't have time to convince you"
 * - Satoshi Nakamoto
 */
public class API {

	private static final Logger log = LoggerFactory.getLogger(API.class.getName());

	/**
	 * <p>Launches a Peer Server with a supplied configuration.</p>
	 *
	 * <p>Config keys are:</p>
	 *
	 * <ul>
	 * <li>:keypair (required) - AKeyPair instance.
	 * <li>:port (optional) - Integer port number to use for incoming connections. Defaults to random allocation.
	 * <li>:store (optional) - AStore instance. Defaults to the configured global store
	 * <li>:source (optional) - URL for Peer to replicate initial State/Belief from.
	 * <li>:state (optional) - Genesis state. Defaults to a fresh genesis state for the Peer if neither :source nor :state is specified
	 * <li>:restore (optional) - Boolean Flag to restore from existing store. Default to true
	 * <li>:persist (optional) - Boolean flag to determine if peer state should be persisted in store at server close. Default true.
	 * </ul>
	 *
	 * @param peerConfig Config map for the new Peer
     *
     * @param event Optional event object that implements the IServerEvent interface
     *
	 * @return New Server instance
	 */
	public static Server launchPeer(Map<Keyword, Object> peerConfig) {
		HashMap<Keyword,Object> config=new HashMap<>(peerConfig);

		// State no8t strictly necessarry? Should be possible to restore a Peer from store
		if (!(config.containsKey(Keywords.STATE)
				||config.containsKey(Keywords.STORE)
				||config.containsKey(Keywords.SOURCE)
				)) {
			throw new IllegalArgumentException("Peer launch requires a genesis :state, remote :source or existing :store in config");
		}

		if (!config.containsKey(Keywords.KEYPAIR)) throw new IllegalArgumentException("Peer launch requires a "+Keywords.KEYPAIR+" in config");

		try {
			if (!config.containsKey(Keywords.PORT)) config.put(Keywords.PORT, null);
			if (!config.containsKey(Keywords.STORE)) config.put(Keywords.STORE, Stores.getGlobalStore());
			if (!config.containsKey(Keywords.RESTORE)) config.put(Keywords.RESTORE, true);
			if (!config.containsKey(Keywords.PERSIST)) config.put(Keywords.PERSIST, true);

			Server server = Server.create(config);
			server.launch();
			return server;
		} catch (Throwable t) {
			log.error("Error launching peer: ",t);
			t.printStackTrace();
			throw Utils.sneakyThrow(t);
		}
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
		return launchLocalPeers(keyPairs, genesisState, null, null);
	}
	/**
	 * Launch a local set of peers. Intended mainly for testing / development.
	 *
	 * The Peers will have a unique genesis State, i.e. an independent network
	 *
	 * @param keyPairs List of keypairs for peers
	 * @param genesisState GEnesis state for local network
	 * @param peerPorts Array of ports to use for each peer, if == null then randomly assign port numbers
	 * @param event Server event handler
	 *
	 * @return List of Servers launched
	 *
	 */
	public static List<Server> launchLocalPeers(List<AKeyPair> keyPairs, State genesisState, int peerPorts[], IServerEvent event) {
		int count=keyPairs.size();

		List<Server> serverList = new ArrayList<Server>();

		Map<Keyword, Object> config = new HashMap<>();

		// Peer should get a new allocated port
		config.put(Keywords.PORT, null);

		// Peers should all have the same genesis state
		config.put(Keywords.STATE, genesisState);

		// TODO maybe have this as an option in the calling parameters?
		AStore store = Stores.current();
		config.put(Keywords.STORE, store);

		// Automatically manage Peer connections
		config.put(Keywords.AUTO_MANAGE, true);

		if (event!=null) {
			config.put(Keywords.EVENT_HOOK, event);
		}

		for (int i = 0; i < count; i++) {
			AKeyPair keyPair = keyPairs.get(i);
			config.put(Keywords.KEYPAIR, keyPair);
			if (peerPorts != null) {
				config.put(Keywords.PORT, peerPorts[i]);
			}
			Server server = API.launchPeer(config);
			serverList.add(server);
		}

		Server genesisServer = serverList.get(0);

		// go through 1..count-1 peers and join them all to the genesis Peer
		// do this twice to allow for all of the peers to get all of the address in the group of peers

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
	 * @param timoutMillis Number of millis to wait before exiting with a failure.
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
