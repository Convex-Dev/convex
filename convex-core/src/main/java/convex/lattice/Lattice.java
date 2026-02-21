package convex.lattice;

import convex.core.cvm.Keywords;
import convex.lattice.data.DataLattice;
import convex.lattice.fs.DLFSLattice;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.MapLattice;
import convex.lattice.generic.OwnerLattice;
import convex.lattice.kv.KVStoreLattice;
import convex.lattice.queue.TopicLattice;

/**
 * Static utility base for the lattice, defining the standard lattice regions.
 * 
 * Lattice applications MAY extend these for their own local / private use 
 * 
 * Registration of namespaces and formal specification via CADs is required for interoperability 
 * and common utility on the public lattice. Violating this rule will probably get your
 * nodes ignored (for unknown nodes) or kicked (for incorrect usage of existing regions)
 */
public class Lattice {

	/**
	 * Base global lattice lattice structure with support for:
	 * - :data - General purpose data storage
	 * - :fs - DLFS replicated filesystem (owner -> drive name -> DLFS node)
	 *   where drive names are AString (not Keywords)
	 * - :kv - Key-value databases (db name -> owner/node -> signed KV store)
	 *   Each database has per-node signed replicas merged via KVStoreLattice
	 * - :queue - Kafka-style message queues (owner -> topic -> :partitions -> partition id -> queue)
	 *   Each topic has metadata + partitions; each partition is an append-only log
	 * - :local - Peer-local storage (owner -> keyword-keyed map of peer-local data)
	 *   Each peer owns a signed slot; safe to replicate via OwnerLattice merge
	 */
	public static KeyedLattice ROOT = KeyedLattice.create(
		Keywords.DATA, DataLattice.INSTANCE,
		Keywords.FS, OwnerLattice.create(
			MapLattice.create(DLFSLattice.INSTANCE)
		),
		Keywords.KV, OwnerLattice.create(
			MapLattice.create(KVStoreLattice.INSTANCE)
		),
		Keywords.QUEUE, OwnerLattice.create(
			MapLattice.create(TopicLattice.INSTANCE)
		),
		LocalLattice.KEY_LOCAL, LocalLattice.LATTICE
	);
}
