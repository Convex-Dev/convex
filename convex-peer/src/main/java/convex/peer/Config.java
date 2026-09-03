package convex.peer;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.PFXTools;
import convex.core.cvm.Keywords;
import convex.core.cvm.Migrations;
import convex.core.cvm.State;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.core.util.FileUtils;
import convex.core.util.Utils;
import convex.etch.EtchConfig;
import convex.etch.EtchKeyDerivation;
import convex.etch.EtchStore;

/**
 * Static tools and utilities for Peer configuration
 */
public class Config {

	/**
	 * Runtime-only launch option for resolving an Etch master key from the
	 * public-key hint in a v3 header. This is deliberately separate from the
	 * serialisable {@code peer.etch} creation policy.
	 */
	public static final Keyword ETCH_KEY_RESOLVER=Keyword.intern("etch-key-resolver");
	
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
	public static final long STATUS_COUNT = 11;

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
	 * Default timeout in milliseconds for client transactions.
	 *
	 * <p>This bounds how long a client waits for a transaction result, so it must
	 * accommodate the slowest machine the client runs on, not the fastest. It is not
	 * a latency target: a transaction normally confirms in tens of milliseconds, and
	 * a value near that would turn ordinary scheduling delay into a spurious
	 * {@code :TIMEOUT} result.</p>
	 */
	public static final long DEFAULT_CLIENT_TIMEOUT = 20000;

	/**
	 * Default timeout in milliseconds for internal waits: establishing a connection,
	 * and offering to a bounded queue before shedding load.
	 *
	 * <p>Deliberately separate from {@link #DEFAULT_CLIENT_TIMEOUT}. These bound
	 * backpressure rather than a user-visible result, so they should stay short even
	 * when clients are given longer to wait.</p>
	 */
	public static final long DEFAULT_INTERNAL_TIMEOUT = 8000;

	/**
	 * Size of incoming Belief queue
	 */
	public static final int BELIEF_QUEUE_SIZE = 200;

	/** Maximum encoded bytes retained by the trusted Belief/DATA queue. */
	public static final int BELIEF_QUEUE_BYTE_LIMIT = 16 * 1024 * 1024;

	/**
	 * Size of bounded queue for Beliefs from unverified inbound connections.
	 * Small — best-effort buffering during the brief verification round-trip.
	 */
	public static final int UNTRUSTED_BELIEF_QUEUE_SIZE = 10;

	/** Maximum encoded bytes retained while an inbound Peer is unverified. */
	public static final int UNTRUSTED_BELIEF_QUEUE_BYTE_LIMIT = 4 * 1024 * 1024;

	/**
	 * Maximum number of inbound client connections accepted by the server.
	 * Each connection consumes ~200KB idle (~300KB under load), so at 1024
	 * connections the total is ~200-300MB (mostly kernel socket buffers).
	 */
	public static final int MAX_CLIENT_CONNECTIONS = 1024;

	/**
	 * Size of bounded outbound message queue per client connection.
	 * Absorbs brief bursts; backpressure kicks in when the queue fills.
	 */
	public static final int OUTBOUND_QUEUE_SIZE = 128;

	/** Maximum ordinary encoded bytes queued per outbound Peer connection. */
	public static final int OUTBOUND_QUEUE_BYTE_LIMIT = 16 * 1024 * 1024;

	/**
	 * Maximum encoded bytes of replies held in a server's shared outbound queue,
	 * not yet handed to Netty. One shared bound absorbs bursts of results across
	 * all inbound connections; bytes leave it as soon as the writer hands them
	 * to the transport, so no single connection can pin it.
	 */
	public static final int SERVER_OUTBOUND_QUEUE_BYTE_LIMIT = 64 * 1024 * 1024;

	/**
	 * Bytes handed to Netty for one inbound connection but not yet written, beyond
	 * which that connection's further replies are refused. Bounds the memory a
	 * reader that stops draining its socket can pin to roughly this much plus one
	 * message, without affecting any other connection.
	 */
	public static final int SERVER_CONNECTION_PENDING_BYTE_LIMIT = 1024 * 1024;

	/** A coalesced priority message must remain a small consensus/control root. */
	public static final int PRIORITY_OUTBOUND_MESSAGE_LIMIT = 64 * 1024;

	/** Peer configuration key for the maximum encoded belief delta chunk size. */
	public static final Keyword MAX_BELIEF_DELTA_MESSAGE_SIZE = Keyword.intern("max-belief-delta-message-size");

	/** Default belief delta chunk size. Large beliefs are sent as DATA-ahead batches. */
	public static final int DEFAULT_MAX_BELIEF_DELTA_MESSAGE_SIZE = 4 * 1024 * 1024;

	/** Peer configuration key for total eager Belief delta materialisation. */
	public static final Keyword MAX_BELIEF_DELTA_BROADCAST_SIZE =
		Keyword.intern("max-belief-delta-broadcast-size");

	/** Default eager Belief delta working set. */
	public static final int DEFAULT_MAX_BELIEF_DELTA_BROADCAST_SIZE = 16 * 1024 * 1024;

	/** Gets and validates the application-specific belief delta chunk limit. */
	public static int getBeliefDeltaMessageSize(Map<Keyword, Object> config) {
		Object configured=config.get(MAX_BELIEF_DELTA_MESSAGE_SIZE);
		int value=(configured==null)
			? DEFAULT_MAX_BELIEF_DELTA_MESSAGE_SIZE
			: Utils.toInt(configured);
		if (value<1 || value>convex.core.cpos.CPoSConstants.MAX_MESSAGE_LENGTH) {
			throw new IllegalArgumentException(MAX_BELIEF_DELTA_MESSAGE_SIZE
				+" must be between 1 and "+convex.core.cpos.CPoSConstants.MAX_MESSAGE_LENGTH
				+": "+value);
		}
		return value;
	}

	/** Gets and validates the total encoded-byte budget for one Belief broadcast. */
	public static int getBeliefDeltaBroadcastSize(Map<Keyword, Object> config) {
		int messageLimit=getBeliefDeltaMessageSize(config);
		Object configured=config.get(MAX_BELIEF_DELTA_BROADCAST_SIZE);
		int defaultValue=Math.max(messageLimit,DEFAULT_MAX_BELIEF_DELTA_BROADCAST_SIZE);
		defaultValue=(int)Math.min(defaultValue,convex.core.cpos.CPoSConstants.MAX_MESSAGE_LENGTH);
		int value=(configured==null)?defaultValue:Utils.toInt(configured);
		if (value<messageLimit || value>convex.core.cpos.CPoSConstants.MAX_MESSAGE_LENGTH) {
			throw new IllegalArgumentException(MAX_BELIEF_DELTA_BROADCAST_SIZE
				+" must be between "+messageLimit+" and "
				+convex.core.cpos.CPoSConstants.MAX_MESSAGE_LENGTH+": "+value);
		}
		return value;
	}

	/**
	 * Checks if the config specifies a valid store
	 * @param config Configuration map for peer
	 * @return Store specified in Config, or null if not specified
	 * @throws IOException 
	 */
	@SuppressWarnings("unchecked")
	public static <T extends AStore> T checkStore(Map<Keyword, Object> config) throws IOException {
		EtchConfig requested=checkConfiguredEtchConfig(config);
		Object o=config.get(Keywords.STORE);
		if (o instanceof AStore) return (T)o;
		
		if ((o instanceof String)||(o instanceof AString)) {
			String fname=o.toString();
			if ("memory".equals(fname)) {
				if (requested!=null) {
					throw new IOException(Keywords.ETCH_CONFIG+" cannot configure an in-memory store");
				}
				return (T) new MemoryStore();
			}
			EtchConfig etchConfig=getEtchConfig(config);
			if ("temp".equals(fname)) {
				return (T) ((etchConfig==null)?EtchStore.createTemp():EtchStore.createTemp(etchConfig));
			}
			File f=FileUtils.getFile(fname);
			return (T) ((etchConfig==null)?EtchStore.create(f):EtchStore.create(f,etchConfig));
		}
		
		return null;
	}

	private static EtchConfig checkConfiguredEtchConfig(Map<Keyword,Object> config) throws IOException {
		Object value=config.get(Keywords.ETCH_CONFIG);
		if (value==null) return null;
		if (value instanceof EtchConfig etchConfig) return etchConfig;
		throw new IOException("Unexpected type for "+Keywords.ETCH_CONFIG+": "+Utils.getClassName(value));
	}

	/**
	 * Gets the effective Etch configuration for a peer or lattice-node store.
	 * Explicit runtime resolution wins; otherwise a configured peer key supplies
	 * the natural resolver. With neither, the configured creation policy is
	 * returned unchanged.
	 *
	 * @param config runtime launch configuration
	 * @return effective Etch configuration, or {@code null} for ordinary defaults
	 * @throws IOException if a configured value has an invalid type
	 */
	@SuppressWarnings("unchecked")
	public static EtchConfig getEtchConfig(Map<Keyword,Object> config) throws IOException {
		EtchConfig etchConfig=checkConfiguredEtchConfig(config);
		Object keyValue=config.get(Keywords.KEYPAIR);
		AKeyPair keyPair=(keyValue instanceof AKeyPair kp)?kp:null;
		Object resolverValue=config.get(ETCH_KEY_RESOLVER);
		Function<AccountKey,byte[]> resolver=null;
		if (resolverValue!=null) {
			if (!(resolverValue instanceof Function<?,?>)) {
				throw new IOException("Unexpected type for "+ETCH_KEY_RESOLVER+": "
						+Utils.getClassName(resolverValue));
			}
			resolver=(Function<AccountKey,byte[]>)resolverValue;
		} else if ((etchConfig!=null)&&etchConfig.hasKeyFunction()) {
			resolver=etchConfig.getKeyFunction();
		} else if (keyPair!=null) {
			resolver=etchKeyResolver(keyPair);
		}
		if ((keyPair!=null)&&(etchConfig!=null)
				&&(etchConfig.getCipherMode()!=EtchConfig.CipherMode.NONE)
				&&(etchConfig.getPublicKeyHint()==null)) {
			etchConfig=etchConfig.withPublicKeyHint(keyPair.getAccountKey());
		}
		if (resolver==null) return etchConfig;
		if (etchConfig==null) etchConfig=EtchConfig.create();
		return etchConfig.withKeyFunction(resolver);
	}

	/**
	 * Creates the standard resolver backed by one Ed25519 key pair. A non-null
	 * hint must identify that key; the private seed is copied only long enough to
	 * derive the Etch master key and is then wiped.
	 *
	 * @param keyPair peer or lattice-node identity key
	 * @return Etch master-key resolver
	 */
	public static Function<AccountKey,byte[]> etchKeyResolver(AKeyPair keyPair) {
		AccountKey publicKey=keyPair.getAccountKey();
		return hint -> {
			if ((hint!=null)&&!hint.equals(publicKey)) {
				throw new IllegalArgumentException("Etch publicKeyHint "+hint
						+" does not match configured key "+publicKey);
			}
			return deriveEtchMasterKey(keyPair);
		};
	}

	/**
	 * Derives the standard Etch master key from an Ed25519 key pair without
	 * retaining its private-seed copy.
	 *
	 * @param keyPair source identity key
	 * @return newly allocated 32-byte Etch master key
	 */
	public static byte[] deriveEtchMasterKey(AKeyPair keyPair) {
		byte[] seed=keyPair.getSeed().getBytes();
		try {
			return EtchKeyDerivation.deriveMasterKey(seed);
		} finally {
			Arrays.fill(seed,(byte)0);
		}
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
		T store;
		try {
			store=checkStore(config);
			if (store==null) {
				EtchConfig etchConfig=getEtchConfig(config);
				store=(T) ((etchConfig==null)?EtchStore.createTemp("tempPeerStore")
						:EtchStore.createTemp("tempPeerStore",etchConfig));
			}
		} catch (IOException e) {
			throw new ConfigException("Unable to configure store due to IO error",e);
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
	 * Applies the configured genesis protocol version to a freshly created genesis
	 * state. A new network has no history to preserve, so it defaults to the latest
	 * supported protocol version ({@link Migrations#MAX_VERSION}) rather than
	 * launching with known-fixed bugs; pin a lower version with
	 * {@code :protocol-version} (e.g. {@code 0} to match a network that has not yet
	 * upgraded). Never applied to an explicitly supplied {@code :state} — a supplied
	 * genesis is respected as-is.
	 *
	 * @param genesis Freshly created genesis state (protocol version 0)
	 * @param config Configuration map, possibly containing {@code :protocol-version}
	 * @return Genesis state at the configured protocol version
	 * @throws ConfigException if the configured version is not an integer in range
	 */
	public static State applyGenesisProtocol(State genesis, Map<Keyword, Object> config) throws ConfigException {
		long target = Migrations.MAX_VERSION;
		Object pv = (config == null) ? null : config.get(Keywords.PROTOCOL_VERSION);
		if (pv != null) {
			CVMLong v = RT.ensureLong(RT.cvm(pv));
			if (v == null || v.longValue() < 0 || v.longValue() > Migrations.MAX_VERSION) {
				throw new ConfigException(":protocol-version must be an integer in 0.." + Migrations.MAX_VERSION
						+ " but was: " + pv);
			}
			target = v.longValue();
		}
		return Migrations.applyTo(genesis, target);
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
