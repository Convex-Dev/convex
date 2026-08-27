package convex.lattice;

import java.util.HashSet;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.LWWLattice;
import convex.lattice.generic.OwnerLattice;

/**
 * Helper for the {@code :p2p} lattice region — peer-to-peer node discovery.
 *
 * <p>Each lattice node publishes a signed {@code NodeInfo} map at
 * {@code :p2p / :nodes / <owner-key>}. The map uses LWW (Last-Writer-Wins)
 * merge on the {@code :timestamp} field so that the latest advertisement from
 * each node always wins.
 *
 * <p>Structure:
 * <pre>
 *   :p2p → KeyedLattice
 *     :nodes → OwnerLattice
 *       node-key-A → Signed(NodeInfo map)
 *       node-key-B → Signed(NodeInfo map)
 * </pre>
 *
 * <p>A NodeInfo map is an {@code AHashMap<Keyword, ACell>} containing:
 * <ul>
 *   <li>{@code :timestamp} — {@code CVMLong} millis, used for LWW ordering</li>
 *   <li>{@code :transports} — {@code AVector<AString>} of public transport URIs
 *       (e.g. {@code "tcp://peer.example.com:18888"}, {@code "wss://peer.example.com:443"}).
 *       Advertised entries must be publicly reachable; an empty vector explicitly
 *       identifies an outbound-only node with no dialable transport.</li>
 *   <li>{@code :pops} — {@code AVector<AccountKey>} of Point of Presence nodes
 *       through which this node expects to be reachable (optional)</li>
 *   <li>{@code :relay} — whether this node is willing to relay bounded signed
 *       point-to-point messages (optional, defaults to false)</li>
 *   <li>{@code :type} — {@code AString} node software type (e.g. {@code "Convex Lattice Node"})</li>
 *   <li>{@code :version} — {@code AString} software version</li>
 *   <li>{@code :regions} — {@code AVector<AString>} region/location tags (optional)</li>
 * </ul>
 *
 * @see LocalLattice
 */
public class P2PLattice {
	/** Maximum Point of Presence declarations accepted in one NodeInfo record. */
	public static final int MAX_POINTS_OF_PRESENCE = 16;

	/**
	 * OwnerLattice for {@code :nodes} — each node owns a signed LWW NodeInfo map.
	 */
	public static final OwnerLattice<ACell> NODES_LATTICE =
		OwnerLattice.create(LWWLattice.INSTANCE);

	/**
	 * KeyedLattice for the {@code :p2p} region, containing {@code :nodes}.
	 */
	public static final KeyedLattice LATTICE =
		KeyedLattice.create(Keywords.NODES, NODES_LATTICE);

	/**
	 * Creates a NodeInfo map with an explicit timestamp (#561).
	 *
	 * <p>The timestamp is supplied by the caller — the driving (merging or test) process —
	 * rather than read from the system clock here; a node stamps its published NodeInfo from
 * its {@code LatticeContext}; publication policy belongs to the P2P application
 * layer rather than the generic lattice transport.</p>
	 *
	 * @param transports Public transport URIs, or empty for an outbound-only node
	 * @param type Node software type (e.g. "Convex Lattice Node")
	 * @param version Software version string
	 * @param regions Region/location tags (may be null or empty)
	 * @param timestamp Timestamp in millis
	 * @return NodeInfo map
	 */
	public static AHashMap<Keyword, ACell> createNodeInfo(
			AVector<AString> transports, AString type, AString version,
			AVector<AString> regions, long timestamp) {
		return createNodeInfo(transports,type,version,regions,null,false,timestamp);
	}

	/**
	 * Creates a NodeInfo map including Point of Presence routing metadata.
	 *
	 * @param transports Public transport URIs, or empty for an outbound-only node
	 * @param type Node software type
	 * @param version Software version string
	 * @param pops Node keys for configured Points of Presence (may be null or empty)
	 * @param relay Whether this node is willing to relay signed point messages
	 * @param timestamp Timestamp in millis
	 * @return NodeInfo map
	 */
	public static AHashMap<Keyword, ACell> createNodeInfo(
			AVector<AString> transports, AString type, AString version,
			AVector<AccountKey> pops, boolean relay, long timestamp) {
		return createNodeInfo(transports,type,version,null,pops,relay,timestamp);
	}

	/**
	 * Creates a complete NodeInfo map including optional regions and PoP metadata.
	 */
	public static AHashMap<Keyword, ACell> createNodeInfo(
			AVector<AString> transports, AString type, AString version,
			AVector<AString> regions, AVector<AccountKey> pops, boolean relay,
			long timestamp) {
		if (pops!=null && pops.count()>MAX_POINTS_OF_PRESENCE) {
			throw new IllegalArgumentException("Too many Points of Presence: "+pops.count());
		}
		if (pops!=null) {
			HashSet<AccountKey> unique=new HashSet<>();
			for (long i=0; i<pops.count(); i++) {
				AccountKey pop=pops.get(i);
				if (pop==null || !unique.add(pop)) {
					throw new IllegalArgumentException("Points of Presence must be unique node keys");
				}
			}
		}
		AHashMap<Keyword, ACell> info = Maps.of(
			Keywords.TIMESTAMP, CVMLong.create(timestamp),
			Keywords.TRANSPORTS, (transports != null) ? transports : Vectors.empty(),
			Keywords.TYPE, type,
			Keywords.VERSION, version,
			Keywords.POPS, (pops != null) ? pops : Vectors.empty(),
			Keywords.RELAY, CVMBool.create(relay)
		);
		if (regions != null && !regions.isEmpty()) {
			info = info.assoc(Keywords.REGIONS, regions);
		}
		return info;
	}

	/**
	 * Signs a NodeInfo map into an OwnerLattice entry for merge at {@code :nodes}.
	 *
	 * @param keyPair The node's key pair
	 * @param nodeInfo NodeInfo map (from {@link #createNodeInfo})
	 * @return Single-entry owner map: {AccountKey → Signed(NodeInfo)}
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static AHashMap<ACell, SignedData<ACell>> createSignedEntry(
			AKeyPair keyPair, AHashMap<Keyword, ACell> nodeInfo) {
		SignedData<ACell> signed = keyPair.signData((ACell) nodeInfo);
		return (AHashMap) Maps.of(keyPair.getAccountKey(), signed);
	}

	/**
	 * Gets the NodeInfo map for a specific node from a merged {@code :nodes} value.
	 *
	 * @param nodesValue The OwnerLattice value (map of AccountKey → SignedData)
	 * @param nodeKey The node's public key
	 * @return NodeInfo map, or null if not present
	 */
	@SuppressWarnings("unchecked")
	public static AHashMap<Keyword, ACell> getNodeInfo(
			AHashMap<ACell, SignedData<ACell>> nodesValue, AccountKey nodeKey) {
		if (nodesValue == null) return null;
		SignedData<ACell> signed = nodesValue.get(nodeKey);
		if (signed == null) return null;
		return (AHashMap<Keyword, ACell>) signed.getValue();
	}

	/**
	 * Gets the signed slot for a specific node from a merged {@code :nodes} value.
	 *
	 * @param nodesValue The OwnerLattice value
	 * @param nodeKey The node's public key
	 * @return SignedData wrapping the NodeInfo map, or null if not present
	 */
	public static SignedData<ACell> getSignedEntry(
			AHashMap<ACell, SignedData<ACell>> nodesValue, AccountKey nodeKey) {
		if (nodesValue == null) return null;
		return nodesValue.get(nodeKey);
	}
}
