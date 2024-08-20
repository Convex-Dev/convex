package convex.peer;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import convex.core.crypto.AKeyPair;
import convex.core.data.AString;
import convex.core.data.Format;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.store.AStore;
import convex.core.util.FileUtils;
import convex.core.util.Utils;
import etch.EtchStore;

/**
 * Static tools and utilities for Peer configuration
 */
public class Config {
	
	/**
	 * Default size for client receive ByteBuffers.
	 */
	public static final int RECEIVE_BUFFER_SIZE = Format.LIMIT_ENCODING_LENGTH*10+20;

	/**
	 * Size of default server socket receive buffer
	 */
	public static final int SOCKET_SERVER_BUFFER_SIZE = 16*65536;

	/**
	 * Size of default server socket buffers for an outbound peer connection
	 */
	public static final int SOCKET_PEER_BUFFER_SIZE = 16*65536;

	/**
	 * Size of default client socket receive buffer
	 */
	public static final int SOCKET_RECEIVE_BUFFER_SIZE = 65536;

	/**
	 * Size of default client socket send buffer
	 */
	public static final int SOCKET_SEND_BUFFER_SIZE = 2*65536;

	/**
	 * Delay before rebroadcasting Belief if not in consensus
	 */
	public static final long MAX_REBROADCAST_DELAY = 200;

	/**
	 * Timeout for syncing with an existing Peer
	 */
	public static final long PEER_SYNC_TIMEOUT = 60000;
	
	/**
	 * Number of milliseconds average time to drop low-staked Peers
	 */
	public static final double PEER_CONNECTION_DROP_TIME = 20000;

	/**
	 * Default number of outgoing connections for a Peer
	 */
	public static final Integer DEFAULT_OUTGOING_CONNECTION_COUNT = 10;


	/**
	 * Number of fields in a Peer STATUS message
	 */
	public static final long STATUS_COUNT = 9;

	/**
	 * Default size for incoming client transaction queue
	 * Note: this limits TPS for client transactions, will send failures if overloaded
	 */
	public static final int TRANSACTION_QUEUE_SIZE = 10000;

	/**
	 * Default size for incoming client query queue
	 * Note: this limits TPS for client queries, will send failures if overloaded
	 */
	public static final int QUERY_QUEUE_SIZE = 10000;
	
	/**
	 * Default timeout in milliseconds for client transactions
	 */
	public static final long DEFAULT_CLIENT_TIMEOUT = 8000;

	/**
	 * Size of incoming Belief queue
	 */
	public static final int BELIEF_QUEUE_SIZE = 200;

	/**
	 * Checks if the config specifies a valid store
	 * @param config Configuration map fpr peer
	 * @return Store specified in Config, or null if not specified
	 */
	@SuppressWarnings("unchecked")
	public static <T extends AStore> T checkStore(Map<Keyword, Object> config) {
		Object o=config.get(Keywords.STORE);
		if (o instanceof AStore) return (T)o;
		
		if ((o instanceof String)||(o instanceof AString)) {
			String fname=o.toString();
			File f=FileUtils.getFile(fname);
			if (f.exists()) {
				try {
					return (T) EtchStore.create(f);
				} catch (IOException e) {
					return null;
				}
			}
		}
		
		return null;
	}
	
	/**
	 * Establishes a store in the given config
	 * @param config Configuration map fpr peer (may be modified)
	 * @return Store specified in Config under :store
	 */
	@SuppressWarnings("unchecked")
	public static  <T extends AStore> T ensureStore(Map<Keyword, Object> config) {
		T store=checkStore(config);
		if (store!=null) return store;
		
		store=(T) EtchStore.createTemp("defaultPeerStore");
		config.put(Keywords.STORE, store);
		return store;
	}
	
	/**
	 * Ensures standard flags are set to defaults(if not specified).
	 * 
	 * @param config Configuration map for peer (may be modified)
	 */
	public static void ensureFlags(Map<Keyword, Object> config) {
		if (!config.containsKey(Keywords.RESTORE)) config.put(Keywords.RESTORE, true);
		if (!config.containsKey(Keywords.PERSIST)) config.put(Keywords.PERSIST, true);
		if (!config.containsKey(Keywords.AUTO_MANAGE)) config.put(Keywords.AUTO_MANAGE, true);
		
		// Port defaults to null, which uses default port if available or picks a random port 
		if (!config.containsKey(Keywords.PORT)) {
			config.put(Keywords.PORT, null);
		}
	}

	/**
	 * Ensures we have a hot peer :keypair set in config
	 * 
	 * @param config Configuration map for peer (may be modified)
	 */
	public static AKeyPair ensurePeerKey(HashMap<Keyword, Object> config) {
		Object o=config.get(Keywords.KEYPAIR);
		if (o!=null) {
			if (o instanceof AKeyPair) {
				AKeyPair kp= (AKeyPair)o;
				return kp;
			}
			throw new ConfigException("Invalid type of :keypair - expected AKeyPair, got "+Utils.getClassName(o));
		} else {
			throw new ConfigException("Peer launch requires a "+Keywords.KEYPAIR+" in config");
		}
	}

}
