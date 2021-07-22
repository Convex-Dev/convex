package convex.peer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.util.Utils;


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

	public static Server launchPeer() {
		Map<Keyword, Object> config = new HashMap<>();
		return launchPeer(config, null);
	}

	public static Server launchPeer(Map<Keyword, Object> config) {
		return launchPeer(config, null);
	}

	/**
	 * <p>Launches a Peer Server with a supplied configuration.</p>
	 *
	 * <p>Config keys are:</p>
	 *
	 * <ul>
	 * <li>:keypair (required) - AKeyPair instance.
	 * <li>:port (optional) - Integer port number to use for incoming connections. Defaults to random allocation.
	 * <li>:store (optional) - AStore instance. Defaults to the configured global store
	 * <li>:state (optional) - Genesis state. Defaults to a fresh genesis state for the Peer.
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
	public static Server launchPeer(Map<Keyword, Object> peerConfig, IServerEvent event) {
		HashMap<Keyword,Object> config=new HashMap<>(peerConfig);
		
		// State no8t strictly necessarry? Should be possible to restore a Peer from store
		if (!(config.containsKey(Keywords.STATE)||config.containsKey(Keywords.STORE))) {
			throw new IllegalArgumentException("Peer launch requires a genesis :state or existing :store in config");
		}
		
		if (!config.containsKey(Keywords.KEYPAIR)) throw new IllegalArgumentException("Peer launch requires a "+Keywords.KEYPAIR+" in config");

		try {
			if (!config.containsKey(Keywords.PORT)) config.put(Keywords.PORT, null);
			if (!config.containsKey(Keywords.STORE)) config.put(Keywords.STORE, Stores.getGlobalStore());
			if (!config.containsKey(Keywords.RESTORE)) config.put(Keywords.RESTORE, true);
			if (!config.containsKey(Keywords.PERSIST)) config.put(Keywords.PERSIST, true);

			Server server = Server.create(config, event);
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
	 * @param genesisState GEnesis state for local network
	 * @param event Server event handler
	 *
	 * @return List of Servers launched
	 *
	 */
	public static List<Server> launchLocalPeers(List<AKeyPair> keyPairs, State genesisState, IServerEvent event) {
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

		for (int i = 0; i < count; i++) {
			AKeyPair keyPair = keyPairs.get(i);
			config.put(Keywords.KEYPAIR, keyPair);
			Server server = API.launchPeer(config, event);
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
		}

		// wait for the peers to sync upto 10 seconds
		//API.waitForNetworkReady(serverList, 10);
		return serverList;
	}

	public static void waitForNetworkReady(List<Server> serverList, long timeoutSeconds) {
		try {
			boolean isNetworkReady = false;
			long lastTimeStamp = 0;
			long timeoutMillis = System.currentTimeMillis() + (timeoutSeconds * 1000);
			while (!isNetworkReady || (timeoutMillis > System.currentTimeMillis())) {
				isNetworkReady = true;
				for (int index = 1; index < serverList.size(); index ++) {
					Server peerServer = serverList.get(index);
					convex.core.Peer peer = peerServer.getPeer();
					if ( peer.getTimeStamp() != lastTimeStamp) {
						lastTimeStamp = peer.getTimeStamp();
						isNetworkReady = false;
						break;
					}
				}
				Thread.sleep(1000);
			}
		} catch ( InterruptedException e) {
			return;
		}
	}
}
