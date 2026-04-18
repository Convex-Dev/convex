/**
 * Generic lattice building blocks — reusable CRDT-like types composed to
 * build richer lattices.
 *
 * <p>Includes last-writer-wins ({@code LWWLattice}), last-writer-priority
 * ({@code LWPLattice}), compare-and-set ({@code CASLattice}), min/max registers,
 * map and set lattices, signed and owner-scoped lattices, and keyed composites.
 * Higher-level lattices (DLFS, KV stores, CNS) are assembled from these.</p>
 */
package convex.lattice.generic;
