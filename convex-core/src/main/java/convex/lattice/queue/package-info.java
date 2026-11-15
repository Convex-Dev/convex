/**
 * Message queues and topics on the lattice: partitioned logs with metadata
 * (<a href="https://docs.convex.world/docs/cad/lattice_queue">CAD040</a>).
 *
 * <p>Work in progress: how concurrent changes to a queue or topic reconcile is not
 * yet designed. The current merges of diverging entry logs and of conflicting
 * metadata keys are placeholders, so applications should not rely on them.</p>
 */
package convex.lattice.queue;
