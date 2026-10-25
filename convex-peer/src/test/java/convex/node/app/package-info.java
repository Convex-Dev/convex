/**
 * Hosted lattice application scenarios using public node APIs: startup,
 * incoming network values, propagation and application-controlled publication.
 * Local in-memory and store-backed scenarios belong in {@code convex.lattice.app}
 * in convex-core. Tests of package-private node machinery remain in convex.node.
 *
 * <p>Network tests bind port zero and wait on completion signals, never sleeps.</p>
 */
package convex.node.app;
