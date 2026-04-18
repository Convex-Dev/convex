/**
 * Etch storage engine — a high-performance content-addressed key-value database
 * used as the persistence layer for Convex lattice data.
 *
 * <p>Etch is append-only and indexed by cryptographic hash, enabling structural
 * sharing, deduplication, and verifiable storage of immutable {@link convex.core.data.ACell}
 * values. It is the default backing store for peers and lattice nodes.</p>
 */
package convex.etch;