/**
 * Wallet abstractions for managing Ed25519 signing keys.
 *
 * <p>Defines the {@code IWallet} / {@code IWalletEntry} interfaces and provides
 * in-memory "hot" wallets and PKCS#12 keystore-backed persistent wallets.
 * Used by the CLI, GUI, and peer startup to sign transactions and blocks.</p>
 */
package convex.core.crypto.wallet;
