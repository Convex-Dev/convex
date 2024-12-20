package convex.peer;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.PFXTools;
import convex.core.cvm.Keywords;
import convex.core.data.AString;
import convex.core.data.Keyword;
import convex.core.store.AStore;
import convex.core.util.FileUtils;
import convex.core.util.Utils;
import convex.etch.EtchStore;

/**
 * Static tools and utilities for Peer configuration
 */
public class Config {
	
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
	 * Flag to use Netty client connections
	 */
	public static final boolean USE_NETTY_CLIENT = true;

	/**
	 * Flag to use Netty server implementation
	 */
	public static final boolean USE_NETTY_SERVER = true;

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
	 * @param config Configuration map for peer
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
	 * Checks if the config specifies a valid keystore
	 * @param config Configuration map for peer
	 * @return Keystore specified in Config, or null if not specified
	 * @throws ConfigException In case keystore is configured incorrectly or not accessible
	 */
	public static KeyStore checkKeyStore(Map<Keyword, Object> config) throws ConfigException {
		Object o=config.get(Keywords.KEYSTORE);
		if (o==null) return null;
		if (o instanceof KeyStore) return (KeyStore)o;
		
		if ((o instanceof String)||(o instanceof AString)) {
			String fname=o.toString();
			File f=FileUtils.getFile(fname);
			if (f.exists()) {
				try {
					char[] pass=Config.checkPass(config,Keywords.STOREPASS);
					KeyStore ks=PFXTools.loadStore(f, pass);
					return ks;
				} catch (GeneralSecurityException e) {
					throw new ConfigException("Security error loading keystore "+fname,e);
				} catch (IOException e) {
					throw new ConfigException("IO Error loading keystore "+fname,e);
				}
			} else {
				throw new ConfigException("Specified keystore "+fname+" does not exist");
			}
		}
		throw new ConfigException("Unexpected type for keystore : "+Utils.getClassName(o));
	}
	
	/**
	 * Gets a password from the config
	 * @param config Config map to check
	 * @param key
	 * @return Password, or null if unspecified
	 * @throws ConfigException 
	 */
	private static char[] checkPass(Map<Keyword, Object> config, Keyword key) throws ConfigException {
		Object po=config.get(key);
		if (po==null) return null;
		if (po instanceof char[]) {
			return (char[]) po;
		}
		if (po instanceof String) {
			char[] cs=((String)po).toCharArray();
			config.put(key, cs);
			return cs;
		}
		throw new ConfigException("Unexpected type for password "+key+" : "+Utils.getClassName(po));
	}

	/**
	 * Establishes a store in the given config
	 * @param config Configuration map fpr peer (may be modified)
	 * @return Store specified in Config under :store
	 * @throws ConfigException in case of store configuration error or not accessible (IO)
	 */
	@SuppressWarnings("unchecked")
	public static  <T extends AStore> T ensureStore(Map<Keyword, Object> config) throws ConfigException {
		T store=checkStore(config);
		if (store!=null) return store;
		
		try {
			store=(T) EtchStore.createTemp("defaultPeerStore");
		} catch (IOException e) {
			throw new ConfigException("Unable to configure temporary store due to IO error",e);
		}
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
		
		// Port defaults to null, which uses default port if available or picks a random port (behaviour of 0)
		if (!config.containsKey(Keywords.PORT)) {
			config.put(Keywords.PORT, null);
		}
	}

	/**
	 * Ensures we have a hot peer :keypair set in config
	 * 
	 * @param config Configuration map for peer (may be modified)
	 * @throws ConfigException in case of configuration problem
	 */
	public static AKeyPair ensurePeerKey(HashMap<Keyword, Object> config) throws ConfigException {
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

	/**
	 * Checks that the config specifies a source for the genesis state
	 * @param config Configuration map for genesis state
	 * @throws ConfigException in case of configuration problem
	 */
	public static void ensureGenesisState(HashMap<Keyword, Object> config) throws ConfigException {
		
		if (!(config.containsKey(Keywords.STATE)
				||config.containsKey(Keywords.STORE)
				||config.containsKey(Keywords.SOURCE)
				)) {
			throw new ConfigException("Peer launch requires a genesis :state, remote :source or existing :store in config");
		}
	}

	/**
	 * Build a Config map
	 * @param kvs key/value arguments
	 * @return Config map
	 */
	public static HashMap<Keyword, Object> of(Object... kvs) {
		int n=kvs.length;
		if ((n%2)!=0) throw new IllegalArgumentException("Needs even number of args (key / value pairs)");
		HashMap<Keyword,Object> hm=new HashMap<>(n/2);
		for (int i=0; i<n; i+=2) {
			Keyword k=(Keyword)kvs[i];
			Object o=kvs[i+1];
			hm.put(k, o);
		}
		return hm;
	}

}
