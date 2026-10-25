package convex.p2p;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;

/**
 * The P2P application's validated view of the node registry at {@code [:p2p :nodes]}.
 *
 * <p>This is the only class that reads the NodeInfo schema. It publishes this node's
 * own signed record through the node-record component, validates and indexes the
 * records of other nodes, and recognises the one update shape that may claim an
 * identity for inbound route authentication. It reads and writes the lattice only.
 * A {@link P2PNode} turns the indexed records into connection intent and asks its
 * propagation group to authenticate routes; nothing here opens a socket, marks a
 * connection trusted or merges a foreign value.</p>
 *
 * <p>The in-memory index is bounded by {@link #setMaxRecords} and is
 * additive/update-only. Lattice absence is not interpreted as revocation because
 * the current Owner/LWW registry has no deletion contract.</p>
 */
final class NodeDirectory {

	private static final Logger log=LoggerFactory.getLogger(NodeDirectory.class);

	/** Bound on indexed remote records until a node supplies its own. */
	static final int DEFAULT_MAX_RECORDS=256;

	/** The registry path within the application root, as a message path. */
	static final AVector<ACell> REGISTRY_PATH=Vectors.of(P2PLattice.KEY_P2P,P2PLattice.KEY_NODES);

	private final P2PApplication application;
	private final ConcurrentHashMap<AccountKey,NodeRecord> records=
		new ConcurrentHashMap<>();
	private volatile int maxRecords=DEFAULT_MAX_RECORDS;

	private AVector<AccountKey> pointsOfPresence=Vectors.empty();
	private boolean relay;

	NodeDirectory(P2PApplication application) {
		this.application=application;
	}

	/** This node's key, supplied explicitly by the application, or null for a keyless node. */
	private AccountKey ownKey() {
		return application.ownKey();
	}

	/** Bounds the number of remote records indexed; a node sets this to its peer bound. */
	void setMaxRecords(int maxRecords) {
		if (maxRecords<0) throw new IllegalArgumentException("Record bound must not be negative");
		this.maxRecords=maxRecords;
	}

	/** Sets the bounded PoP declarations used by the next NodeInfo publication. */
	void setPointsOfPresence(AVector<AccountKey> pops) {
		AVector<AccountKey> value=(pops==null) ? Vectors.empty() : pops;
		if (value.count()>convex.lattice.p2p.P2PLattice.MAX_POINTS_OF_PRESENCE) {
			throw new IllegalArgumentException("Too many Points of Presence: "+value.count());
		}
		AccountKey ownKey=ownKey();
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
	 * Publishes this node's complete signed NodeInfo advertising {@code url}, or no
	 * transport when {@code url} is null, then syncs the host. The record is written
	 * through the node-record component, so it replaces any retained own record
	 * rather than merging with it: a node always advertises its current record. A
	 * keyless application publishes nothing.
	 *
	 * @param url advertised transport, or null when this node accepts no inbound connections
	 */
	void publishOwnRecord(AString url) {
		AccountKey ownKey=ownKey();
		if (ownKey==null) return;
		if (application.getLattice().path(P2PLattice.KEY_P2P,P2PLattice.KEY_NODES)==null) {
			throw new IllegalStateException("P2P application root does not contain [:p2p :nodes]");
		}

		AVector<AString> transports=(url==null) ? Vectors.empty() : Vectors.of(url);
		AString type=Strings.create("Convex Lattice Node");
		String currentVersion=Utils.getVersion();
		AString version=Strings.create(currentVersion==null ? "unknown" : currentVersion);
		long timestamp=application.clock().get().longValue();
		AHashMap<Keyword,ACell> info=convex.lattice.p2p.P2PLattice.createNodeInfo(
			transports,type,version,null,pointsOfPresence,relay,timestamp);

		application.node(ownKey).setNodeInfo(info);
		application.syncHost();
		log.debug("Published NodeInfo: transports={}, pops={}, relay={}, type={}, version={}",
			transports,pointsOfPresence,relay,type,version);
	}

	/** The signed registry entry this node has published, as a one-entry owner map, or null. */
	@SuppressWarnings({"unchecked","rawtypes"})
	AHashMap<ACell,SignedData<ACell>> ownEntry() {
		AccountKey ownKey=ownKey();
		if (ownKey==null) return null;
		ACell raw=application.cursor().get(P2PLattice.KEY_P2P,P2PLattice.KEY_NODES);
		if (!(raw instanceof AHashMap<?,?> nodes)) return null;
		ACell signed=((AHashMap<ACell,ACell>)nodes).get(ownKey);
		if (!(signed instanceof SignedData<?>)) return null;
		return (AHashMap) Maps.of(ownKey,signed);
	}

	/** Root and {@code :p2p} merges may both carry a newer nested registry value. */
	static boolean mayAffectRegistry(ACell[] path) {
		return path!=null && (path.length==0
			|| (path.length>0 && P2PLattice.KEY_P2P.equals(path[0])));
	}

	/**
	 * Returns the owner of one valid complete NodeInfo update delivered at exactly
	 * the registry path, otherwise null. Only this shape may claim the identity used
	 * to start inbound possession proof.
	 */
	AccountKey claimedOwner(ACell[] path,ACell value) {
		return isRegistryPath(path) ? singleValidatedOwner(value) : null;
	}

	/**
	 * Rebuilds the bounded validated index from the current registry and returns
	 * the remote records that are new or newer than before, for the node to turn
	 * into connection intent.
	 */
	@SuppressWarnings("unchecked")
	synchronized List<NodeRecord> refresh() {
		List<NodeRecord> changed=new ArrayList<>();
		ACell value=application.cursor().get(P2PLattice.KEY_P2P,P2PLattice.KEY_NODES);
		if (!(value instanceof AHashMap<?,?> rawNodes)) return changed;
		AHashMap<ACell,SignedData<ACell>> nodes=(AHashMap<ACell,SignedData<ACell>>)rawNodes;
		AccountKey ownKey=ownKey();
		int limit=maxRecords;
		for (Map.Entry<ACell,SignedData<ACell>> entry:nodes.entrySet()) {
			AccountKey peerKey=RT.ensureAccountKey(entry.getKey());
			if (peerKey==null) continue;
			NodeRecord updated=parseRecord(peerKey,entry.getValue());
			if (updated==null) continue;

			NodeRecord existing=records.get(peerKey);
			if (existing!=null && updated.timestamp()<=existing.timestamp()) continue;
			boolean own=peerKey.equals(ownKey);
			if (existing==null && !own && remoteRecordCount(ownKey)>=limit) continue;
			records.put(peerKey,updated);
			if (!own) changed.add(updated);
		}
		return changed;
	}

	/** Returns a defensive snapshot used by the transient PoP route planner. */
	Map<AccountKey,NodeRecord> records() {
		return new HashMap<>(records);
	}

	private int remoteRecordCount(AccountKey ownKey) {
		int size=records.size();
		return ownKey!=null && records.containsKey(ownKey) ? size-1 : size;
	}

	private static boolean isRegistryPath(ACell[] path) {
		return path!=null && path.length==2
			&& P2PLattice.KEY_P2P.equals(path[0])
			&& P2PLattice.KEY_NODES.equals(path[1]);
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
		if (rawPops.count()>convex.lattice.p2p.P2PLattice.MAX_POINTS_OF_PRESENCE) return null;
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
