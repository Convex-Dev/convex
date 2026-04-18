/**
 * Cryptographic primitives used throughout Convex.
 *
 * <p>Provides Ed25519 digital signatures (via Bouncy Castle), SHA-256 / SHA-3 hashing,
 * secure key generation, and signed envelope types. These primitives underpin peer
 * identity, transaction signing, and the content-addressed integrity of all
 * {@link convex.core.data.ACell} values on the network.</p>
 */
package convex.core.crypto;