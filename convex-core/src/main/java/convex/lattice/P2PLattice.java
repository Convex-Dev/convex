package convex.lattice;

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
 *       Must be publicly reachable — never localhost or private addresses.</li>
 *   <li>{@code :type} — {@code AString} node software type (e.g. {@code "Convex Lattice Node"})</li>
 *   <li>{@code :version} — {@code AString} software version</li>
 *   <li>{@code :regions} — {@code AVector<AString>} region/location tags (optional)</li>
 * </ul>
 *
 * @see LocalLattice
 */
public class P2PLattice {

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
	 * Creates a NodeInfo map with the given fields and current timestamp.
	 *
	 * @param transports Public transport URIs (must be publicly reachable, never localhost)
	 * @param type Node software type (e.g. "Convex Lattice Node")
	 * @param version Software version string
	 * @param regions Region/location tags (may be null or empty)
	 * @return NodeInfo map suitable for signing and publishing
	 */
	public static AHashMap<Keyword, ACell> createNodeInfo(
			AVector<AString> transports, AString type, AString version, AVector<AString> regions) {
		return createNodeInfo(transports, type, version, regions, System.currentTimeMillis());
	}

	/**
	 * Creates a NodeInfo map with explicit timestamp (for testing).
	 *
	 * @param transports Public transport URIs (must be publicly reachable, never localhost)
	 * @param type Node software type (e.g. "Convex Lattice Node")
	 * @param version Software version string
	 * @param regions Region/location tags (may be null or empty)
	 * @param timestamp Timestamp in millis
	 * @return NodeInfo map
	 */
	public static AHashMap<Keyword, ACell> createNodeInfo(
			AVector<AString> transports, AString type, AString version,
			AVector<AString> regions, long timestamp) {
		AHashMap<Keyword, ACell> info = Maps.of(
			Keywords.TIMESTAMP, CVMLong.create(timestamp),
			Keywords.TRANSPORTS, (transports != null) ? transports : Vectors.empty(),
			Keywords.TYPE, type,
			Keywords.VERSION, version
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
