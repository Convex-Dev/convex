/**
 * Core lattice abstractions — the foundation of Convex's convergent data model.
 *
 * <p>A {@link convex.lattice.ALattice} defines a set of values and an ordered
 * merge operation. Many concrete merges are commutative, associative and
 * idempotent, giving the usual coordination-free CRDT behaviour. Commutativity
 * is not a framework-wide guarantee, however: a lattice may deliberately use
 * the first ({@code own}) argument to resolve an otherwise ambiguous conflict,
 * such as distinct LWW values with equal timestamps. Callers must therefore
 * preserve the documented {@code own}/{@code other} roles and must not reorder
 * or fold merges unless the concrete lattice permits it.</p>
 *
 * <p>This package provides the base lattice interfaces, {@code LatticeContext},
 * and local/peer-to-peer runtime implementations that underpin DLFS, key-value
 * lattices, and higher-level Covia data types.</p>
 *
 * <p>Subpackages supply generic lattice building blocks ({@link convex.lattice.generic}),
 * cursor-based traversal ({@link convex.lattice.cursor}), file systems
 * ({@link convex.lattice.fs}), and key-value stores ({@link convex.lattice.kv}).</p>
 */
package convex.lattice;
