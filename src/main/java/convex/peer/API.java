package convex.peer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import convex.core.Init;
import convex.core.crypto.AKeyPair;
import convex.core.data.Address;
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

	private static final Logger log = Logger.getLogger(API.class.getName());

	public static Server launchPeer() {
		Map<Keyword, Object> config = new HashMap<>();
		return launchPeer(config, null);
	}

	public static Server launchPeer(Map<Keyword, Object> config) {
		return launchPeer(config, null);
	}

	/**
	 * <p>Launches a Peer Server with a default configuration.</p>
	 *
	 * <p>Config keys are:</p>
	 *
	 * <ul>
	 * <li>:port (optional) - Integer port number to use for incoming connections. Defaults to random.
	 * <li>:store (optional) - AStore instance. Defaults to the configured global store
	 * <li>:keypair (optional) - AKeyPair instance. Defaults to first auto-generated Peer keyPair;
	 * <li>:state (optional) - Initialisation state. Only used if initialising a new Peer.
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
		try {
			if (!config.containsKey(Keywords.PORT)) config.put(Keywords.PORT, null);
			if (!config.containsKey(Keywords.STORE)) config.put(Keywords.STORE, Stores.getGlobalStore());
			if (!config.containsKey(Keywords.KEYPAIR)) config.put(Keywords.KEYPAIR, Init.KEYPAIRS[0]);
			if (!config.containsKey(Keywords.STATE)) config.put(Keywords.STATE, Init.createState());
			if (!config.containsKey(Keywords.RESTORE)) config.put(Keywords.RESTORE, true);
			if (!config.containsKey(Keywords.PERSIST)) config.put(Keywords.PERSIST, true);

			Server server = Server.create(config, event);
			server.launch();
			return server;
		} catch (Throwable t) {
			log.warning("Error launching peer: "+t.getMessage());
			t.printStackTrace();
			throw Utils.sneakyThrow(t);
		}
	}

	/**
	 * Launch a set of peers.
	 *
	 * @param count Number of peers to launch.
	 *
	 * @param keyPairs Array of keyPairs for each peer. The length of the array must be >= the count of peers to launch.
	 * @throws IOException 
	 *
	 */
	public static List<Server> launchLocalPeers(int count, AKeyPair[] keyPairs, Address peerAddress, IServerEvent event) {
		List<Server> serverList = new ArrayList<Server>();
		Server otherServer;
		String remotePeerHostname;

		Map<Keyword, Object> config = new HashMap<>();

		config.put(Keywords.PORT, null);
		config.put(Keywords.STATE, Init.createState());

		// TODO maybe have this as an option in the calling parameters
		AStore store = Stores.getGlobalStore();
		Stores.setCurrent(store);
		config.put(Keywords.STORE, store);

		for (int i = 0; i < count; i++) {
			AKeyPair keyPair = keyPairs[i];
			config.put(Keywords.KEYPAIR, keyPair);
			Server server = API.launchPeer(config, event);
			serverList.add(server);
		}

		Server genesisServer = serverList.get(0);

		// go through 1..count-1 peers and join them all to peer #0
		// do this twice to allow for all of the peers to get all of the address in the group of peers

		for (int i = 1; i < count; i++) {
			Server server=serverList.get(i);
			remotePeerHostname = genesisServer.getHostname();
			
			try {
				// Join this Server to the Seer #0
				serverList.get(i).connectToPeer(genesisServer.getPeerKey(), genesisServer.getHostAddress());
				// Join server #0 to this server
				genesisServer.connectToPeer(server.getPeerKey(), server.getHostAddress());
			} catch (IOException e) {
				log.severe("Failed to connect peers" +e.getMessage());
			}
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
