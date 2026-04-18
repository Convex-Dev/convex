/**
 * Core lattice abstractions — the foundation of Convex's convergent data model.
 *
 * <p>A {@link convex.lattice.ALattice} defines a set of values equipped with a
 * commutative, associative, idempotent merge operation, so that independent
 * replicas can reconcile deterministically without coordination (CRDT-style).
 * This package provides the base lattice interfaces, {@code LatticeContext},
 * and local/peer-to-peer runtime implementations that underpin DLFS, key-value
 * lattices, and higher-level Covia data types.</p>
 *
 * <p>Subpackages supply generic lattice building blocks ({@link convex.lattice.generic}),
 * cursor-based traversal ({@link convex.lattice.cursor}), file systems
 * ({@link convex.lattice.fs}), and key-value stores ({@link convex.lattice.kv}).</p>
 */
package convex.lattice;
