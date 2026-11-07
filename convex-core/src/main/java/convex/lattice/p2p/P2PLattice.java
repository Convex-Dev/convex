package convex.lattice.p2p;

import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.LWWLattice;
import convex.lattice.generic.OwnerMapLattice;

/**
 * The {@code :p2p} lattice region — peer-to-peer node discovery.
 *
 * <p>Each node publishes a record signed by its own key at
 * {@code [:p2p :nodes <node-key>]}. Records merge by LWW (Last-Writer-Wins) on their
 * {@code :timestamp} field, so the latest record from each node wins, and only the
 * owner's key may sign its slot.
 *
 * <p>Structure:
 * <pre>
 *   :p2p → KeyedLattice
 *     :nodes → OwnerLattice
 *       node-key-A → Signed(NodeInfo map)
 *       node-key-B → Signed(NodeInfo map)
 * </pre>
 *
 * <p>This class declares only the region's merge rule. The record format, its
 * validation and which records a node keeps belong to the P2P application in
 * {@code convex-p2p}.
 */
public class P2PLattice {

	/**
	 * OwnerLattice for {@code :nodes} — each node owns a signed LWW record.
	 */
	public static final OwnerMapLattice<ACell,ACell> NODES_LATTICE =
		OwnerMapLattice.create(LWWLattice.INSTANCE,(owner,key) -> key.equals(owner));

	/**
	 * KeyedLattice for the {@code :p2p} region, containing {@code :nodes}.
	 */
	public static final KeyedLattice LATTICE =
		KeyedLattice.create(Keywords.NODES, NODES_LATTICE);
}
