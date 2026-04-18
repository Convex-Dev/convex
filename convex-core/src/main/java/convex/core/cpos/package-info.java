/**
 * Convergent Proof of Stake (CPoS) consensus algorithm.
 *
 * <p>Implements the ordering mechanism by which peers propose signed blocks,
 * merge {@code Belief} lattices, and converge on a total transaction order
 * without leader election or forking. This package defines blocks, beliefs,
 * orders, and the peer-side state used to run the consensus loop.</p>
 *
 * <p>See: <a href="https://docs.convex.world/docs/overview/convex-whitepaper">Convex Whitepaper</a></p>
 */
package convex.core.cpos;