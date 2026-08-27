package convex.p2p;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.message.AConnection;
import convex.core.util.Utils;
import convex.node.LatticeConnectionManager;
import convex.node.LatticePropagator;
import convex.node.NodeConfig;
import convex.node.NodeServer;

/**
 * Owns the P2P node registry policy layered over a generic {@link NodeServer}.
 *
 * <p>This class is the only bridge between the application-specific
 * {@code [:p2p :nodes]} lattice and generic connection intent. It publishes this
 * node's signed {@code NodeInfo}, validates and indexes remote records, translates
 * their transport declarations into {@link LatticeConnectionManager} intent, and
 * asks the transport to authenticate an inbound route only after a valid owned
 * NodeInfo update has been accepted by the lattice.</p>
 *
 * <p>It deliberately does not open sockets itself, mark connections trusted or
 * merge lattice values. Socket admission and trust remain propagation-group
 * concerns; authoritative merge remains a NodeServer concern. Conversely, none
 * of those generic transport classes knows the registry path or NodeInfo schema.</p>
 *
 * <p>The in-memory index is bounded by {@link NodeConfig#getMaxDesiredPeers()} and
 * additive/update-only. Lattice absence is not interpreted as revocation because
 * the current Owner/LWW registry has no deletion contract.</p>
 */
final class NodeDirectory {

	private static final Logger log=LoggerFactory.getLogger(NodeDirectory.class);

	private final NodeServer<?> server;
	private final LatticePropagator propagator;
	private final AKeyPair keyPair;
	private final AccountKey ownKey;
	private final ConcurrentHashMap<AccountKey,NodeRecord> records=
		new ConcurrentHashMap<>();

	private AVector<AccountKey> pointsOfPresence=Vectors.empty();
	private boolean relay;

	NodeDirectory(NodeServer<?> server,LatticePropagator propagator,AKeyPair keyPair) {
		this.server=server;
		this.propagator=propagator;
		this.keyPair=keyPair;
		this.ownKey=(keyPair==null) ? null : keyPair.getAccountKey();
	}

	/** Validates P2P advertisement settings before the generic server opens resources. */
	void validateLaunchConfiguration() {
		AString configured=server.getConfig().getURL();
		if (configured==null) return;
		String reason=NodeConfig.validatePublicURL(
			configured.toString(),server.getConfig().isAllowPrivateURL());
		if (reason!=null) {
			throw new IllegalStateException("Invalid node URL configuration: "+reason);
		}
	}

	/** Sets the bounded PoP declarations used by the next NodeInfo publication. */
	void setPointsOfPresence(AVector<AccountKey> pops) {
		AVector<AccountKey> value=(pops==null) ? Vectors.empty() : pops;
		if (value.count()>convex.lattice.P2PLattice.MAX_POINTS_OF_PRESENCE) {
			throw new IllegalArgumentException("Too many Points of Presence: "+value.count());
		}
		HashSet<AccountKey> unique=new HashSet<>();
		for (long i=0; i<value.count(); i++) {
			AccountKey pop=value.get(i);
			if (pop==null || pop.equals(ownKey) || !unique.add(pop)) {
				throw new IllegalArgumentException(
					"Points of Presence must be unique remote node keys");
			}
		}
		pointsOfPresence=value;
	}

	/** Sets whether the next NodeInfo advertises bounded point-message relay. */
	void setRelay(boolean relay) {
		this.relay=relay;
	}

	/**
	 * Publishes this node's complete signed NodeInfo after the listener has bound and
	 * therefore knows its actual port. A keyless node deliberately publishes nothing.
	 */
	void publishOwnRecord() {
		if (keyPair==null) return;
		if (server.getLattice().path(P2PLattice.KEY_P2P,P2PLattice.KEY_NODES)==null) {
			throw new IllegalStateException("P2PNode root does not contain [:p2p :nodes]");
		}

		AString url=server.getConfig().getAdvertisedURL(server.getPort());
		AVector<AString> transports=(url==null) ? Vectors.empty() : Vectors.of(url);
		AString type=Strings.create("Convex Lattice Node");
		String currentVersion=Utils.getVersion();
		AString version=Strings.create(currentVersion==null ? "unknown" : currentVersion);
		long timestamp=server.getCursor().getContext().currentTimestampValue();
		AHashMap<Keyword,ACell> info=convex.lattice.P2PLattice.createNodeInfo(
			transports,type,version,null,pointsOfPresence,relay,timestamp);
		AHashMap<ACell,SignedData<ACell>> entry=
			convex.lattice.P2PLattice.createSignedEntry(keyPair,info);

		server.getCursor().path(P2PLattice.KEY_P2P,P2PLattice.KEY_NODES).merge(entry);
		server.getCursor().sync();
		refresh();
		log.info("Published NodeInfo: transports={}, pops={}, relay={}, type={}, version={}",
			transports,pointsOfPresence,relay,type,version);
	}

	/**
	 * Handles the generic transport's notification for one accepted inbound value.
	 * Root, {@code :p2p} and exact registry merges can refresh discovery; only a
	 * strict one-record update at the exact registry path can claim the identity
	 * used to start inbound possession proof.
	 */
	void onAcceptedInbound(AConnection connection,LatticePropagator propagator,
			ACell[] path,ACell value,boolean changed) {
		if (!mayAffectNodeRegistry(path)) return;
		AccountKey claimedKey=isNodeRegistryPath(path)
			? singleValidatedOwner(value) : null;
		refresh();
		if (connection!=null && claimedKey!=null) {
			propagator.authenticateInboundRoute(connection,claimedKey);
		}
	}

	/**
	 * Rebuilds the bounded validated index from the merged registry and translates
	 * accepted transport metadata into this application's propagation group.
	 */
	@SuppressWarnings("unchecked")
	synchronized void refresh() {
		ACell value=server.getCursor().get(P2PLattice.KEY_P2P,P2PLattice.KEY_NODES);
		if (!(value instanceof AHashMap<?,?> rawNodes)) return;
		AHashMap<ACell,SignedData<ACell>> nodes=(AHashMap<ACell,SignedData<ACell>>)rawNodes;
		int limit=server.getConfig().getMaxDesiredPeers();
		for (Map.Entry<ACell,SignedData<ACell>> entry:nodes.entrySet()) {
			AccountKey peerKey=RT.ensureAccountKey(entry.getKey());
			if (peerKey==null) continue;
			NodeRecord updated=parseRecord(peerKey,entry.getValue());
			if (updated==null) continue;

			NodeRecord existing=records.get(peerKey);
			if (existing!=null && updated.timestamp()<=existing.timestamp()) continue;
			if (existing==null && !peerKey.equals(ownKey)
					&& remoteRecordCount()>=limit) continue;
			records.put(peerKey,updated);
			if (peerKey.equals(ownKey)) continue;
			propagator.getConnectionManager().updateDiscoveredPeer(
				peerKey,updated.transports(),updated.timestamp());
		}
	}

	/** Returns a defensive snapshot used by the transient PoP route planner. */
	Map<AccountKey,NodeRecord> records() {
		return new HashMap<>(records);
	}

	private int remoteRecordCount() {
		int size=records.size();
		return ownKey!=null && records.containsKey(ownKey) ? size-1 : size;
	}

	private static boolean isNodeRegistryPath(ACell[] path) {
		return path!=null && path.length==2
			&& P2PLattice.KEY_P2P.equals(path[0])
			&& P2PLattice.KEY_NODES.equals(path[1]);
	}

	/** Root and {@code :p2p} merges may both carry a newer nested registry value. */
	private static boolean mayAffectNodeRegistry(ACell[] path) {
		return path!=null && (path.length==0
			|| (path.length>0 && P2PLattice.KEY_P2P.equals(path[0])));
	}

	/** Returns the owner of one valid complete NodeInfo update, otherwise null. */
	@SuppressWarnings("unchecked")
	private static AccountKey singleValidatedOwner(ACell value) {
		if (!(value instanceof AHashMap<?,?> raw) || raw.count()!=1) return null;
		AHashMap<ACell,ACell> map=(AHashMap<ACell,ACell>)raw;
		Map.Entry<ACell,ACell> entry=map.entrySet().iterator().next();
		AccountKey key=RT.ensureAccountKey(entry.getKey());
		if (key==null || !(entry.getValue() instanceof SignedData<?> signed)) return null;
		return parseRecord(key,signed)==null ? null : key;
	}

	/** Strictly validates a signed NodeInfo and normalises its routing fields. */
	@SuppressWarnings({"unchecked","rawtypes"})
	private static NodeRecord parseRecord(AccountKey peerKey,SignedData<?> signed) {
		if (signed==null || !peerKey.equals(signed.getAccountKey())
				|| !signed.checkSignature()) return null;
		if (!(signed.getValue() instanceof AHashMap<?,?> rawInfo)) return null;
		AHashMap<Keyword,ACell> info=(AHashMap<Keyword,ACell>)rawInfo;

		AVector<?> rawTransports=RT.ensureVector(info.get(Keywords.TRANSPORTS));
		if (rawTransports==null) return null;
		for (long i=0; i<rawTransports.count(); i++) {
			if (RT.ensureString(rawTransports.get(i))==null) return null;
		}
		AVector<AString> transports=(AVector)rawTransports;

		ACell rawType=info.get(Keywords.TYPE);
		ACell rawVersion=info.get(Keywords.VERSION);
		AString type=RT.ensureString(rawType);
		AString version=RT.ensureString(rawVersion);
		if ((rawType!=null && type==null) || (rawVersion!=null && version==null)) return null;

		AVector<?> rawPops=RT.ensureVector(info.get(Keywords.POPS));
		if (rawPops==null) rawPops=Vectors.empty();
		if (rawPops.count()>convex.lattice.P2PLattice.MAX_POINTS_OF_PRESENCE) return null;
		HashSet<AccountKey> unique=new HashSet<>();
		AVector<AccountKey> pops=Vectors.empty();
		for (long i=0; i<rawPops.count(); i++) {
			AccountKey pop=RT.ensureAccountKey(rawPops.get(i));
			if (pop==null || pop.equals(peerKey) || !unique.add(pop)) return null;
			pops=pops.conj(pop);
		}

		ACell rawRelay=info.get(Keywords.RELAY);
		if (rawRelay!=null && !(rawRelay instanceof CVMBool)) return null;
		boolean relay=(rawRelay instanceof CVMBool flag) && flag.booleanValue();
		CVMLong timestamp=RT.ensureLong(info.get(Keywords.TIMESTAMP));
		if (timestamp==null) return null;
		return new NodeRecord(peerKey,transports,type,version,pops,relay,timestamp.longValue());
	}

	/**
	 * Immutable validated NodeInfo projection retained for P2P discovery and relay
	 * routing. Possession of a record does not authenticate a live connection.
	 */
	record NodeRecord(AccountKey peerKey,AVector<AString> transports,AString type,
		AString version,AVector<AccountKey> pointsOfPresence,boolean relay,long timestamp) {}
}
