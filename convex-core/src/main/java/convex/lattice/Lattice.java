package convex.lattice;

import convex.core.cvm.Keywords;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.lattice.data.DataLattice;
import convex.lattice.fs.DLFSLattice;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.MapLattice;
import convex.lattice.generic.OwnerLattice;
import convex.lattice.kv.KVStoreLattice;
import convex.lattice.queue.TopicLattice;

/**
 * Static utility base for the lattice
 */
public class Lattice {

	/**
	 * ROOT lattice structure with support for:
	 * - :data - General purpose data storage
	 * - :fs - DLFS replicated filesystem (owner -> drive name -> DLFS node)
	 *   where drive names are AString (not Keywords)
	 * - :kv - Key-value databases (db name -> owner/node -> signed KV store)
	 *   Each database has per-node signed replicas merged via KVStoreLattice
	 * - :queue - Kafka-style message queues (owner -> topic -> :partitions -> partition id -> queue)
	 *   Each topic has metadata + partitions; each partition is an append-only log
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
		)
	);
}
