/**
 * Core implementation of the Convex network and virtual machine.
 *
 * <p>The {@code convex.core} package and its subpackages contain the reference
 * implementation of the Convex protocol, including the Convex Virtual Machine (CVM),
 * peer consensus, belief propagation, on-chain state management, transaction processing,
 * cryptography, and all data structures that are canonical on the network.
 *
 * <p>Key components include:
 * <ul>
 *   <li>{@link convex.core.data} – immutable persistent CVM data structures and primitives</li>
 *   <li>{@link convex.core.lang} – the CVM execution engine and standard library</li>
 *   <li>{@link convex.core.cpos} – Convergent Proof of Stake consensus algorithm</li>
 *   <li>{@link convex.core.store} – Etch database for lattice storage</li>
 * </ul>
 *
 * <p>All code in {@code convex.core} is required to be deterministic, versioned,
 * and strictly compliant with the Convex CADs. It forms the trusted
 * foundation used by peers, clients, and tooling across the ecosystem.
 *
 * <p>Thread-safety: most classes are immutable or explicitly documented where
 * mutable state exists (e.g. {@link convex.core.data.util.BlobBuilder}). The package is designed
 * for concurrent use by network threads after peer startup.
 */
package convex.core;