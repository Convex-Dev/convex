package convex.peer;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import convex.core.Init;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
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
		return launchPeer(config);
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
	 * @return New Server instance
	 */
	public static Server launchPeer(Map<Keyword, Object> peerConfig) {
		HashMap<Keyword,Object> config=new HashMap<>(peerConfig);
		try {
			if (!config.containsKey(Keywords.PORT)) config.put(Keywords.PORT, null);
			if (!config.containsKey(Keywords.STORE)) config.put(Keywords.STORE, Stores.getGlobalStore());
			// if (!config.containsKey(Keywords.KEYPAIR)) config.put(Keywords.KEYPAIR, Init.KEYPAIRS[0]);
			if (!config.containsKey(Keywords.STATE)) config.put(Keywords.STATE, Init.createState());
			if (!config.containsKey(Keywords.RESTORE)) config.put(Keywords.RESTORE, true);
			if (!config.containsKey(Keywords.PERSIST)) config.put(Keywords.PERSIST, true);

			Server server = Server.create(config);
			server.launch();
			return server;
		} catch (Throwable t) {
			log.warning("Error launching peer: "+t.getMessage());
			t.printStackTrace();
			throw Utils.sneakyThrow(t);
		}
	}
}
