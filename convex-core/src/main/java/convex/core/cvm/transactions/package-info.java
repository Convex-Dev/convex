/**
 * Signed transaction types that drive state transitions on the Convex network.
 *
 * <p>Includes {@code Invoke} (execute CVM code), {@code Transfer} (move coins),
 * {@code Call} (invoke an actor function), and {@code Multi} (batched transactions).
 * Each transaction carries an origin account, sequence number, and is wrapped in
 * a signed envelope before being submitted to peers.</p>
 */
package convex.core.cvm.transactions;