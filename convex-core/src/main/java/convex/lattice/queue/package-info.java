/**
 * Message queues and topics on the lattice: partitioned logs with metadata
 * (<a href="https://docs.convex.world/docs/cad/lattice_queue">CAD040</a>).
 *
 * <p>A bare queue assumes no order between writers, so publishing a fork after entries
 * were appended both there and at its destination is a conflict. How diverging entry
 * logs merge between replicas, and conflicting metadata keys, is not yet designed: the
 * current merges are placeholders, so applications should not rely on them.</p>
 */
package convex.lattice.queue;
