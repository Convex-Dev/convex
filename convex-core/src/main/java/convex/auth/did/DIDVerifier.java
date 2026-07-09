package convex.auth.did;

import convex.core.crypto.ASignature;
import convex.core.crypto.util.Multikey;
import convex.core.cvm.AccountStatus;
import convex.core.cvm.Address;
import convex.core.cvm.State;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Blob;

/**
 * Predicate answering: does {@code signature} verify as a signature by the subject of
 * {@code did} over {@code message}?
 *
 * <p>The implementation encapsulates both DID resolution and the signature scheme —
 * callers never see keys, DID documents, or verification methods. This keeps the seam
 * minimal while allowing arbitrary DID methods and signature schemes to be plugged in.</p>
 *
 * <p><b>Fail-closed contract:</b> an implementation should return {@code false} for any
 * DID or scheme it cannot verify, and should never throw. Enforcement points additionally
 * wrap calls so a defective implementation is still treated as a denial.</p>
 *
 * <p>The {@code message} is exactly the bytes that were signed — the caller supplies the
 * correct signing input for the token encoding in use (CAD3 {@code Ref} encoding for
 * native tokens, base64url {@code header.payload} for JWT), so implementations are
 * encoding-agnostic.</p>
 */
@FunctionalInterface
public interface DIDVerifier {

	/**
	 * Check whether {@code signature} is a valid signature by the subject of {@code did}
	 * over {@code message}.
	 *
	 * @param did DID of the claimed signer (e.g. {@code did:key:z6Mk...})
	 * @param message Exact bytes that were signed
	 * @param signature Raw signature bytes
	 * @return true iff the signature verifies for this DID
	 */
	boolean verifies(AString did, Blob message, Blob signature);

	/**
	 * Stateless verifier for {@code did:key} issuers: the DID encodes the Ed25519 public
	 * key directly (multikey), so verification is pure computation. Any other DID method
	 * yields {@code false}.
	 */
	DIDVerifier CONVEX = (did, message, signature) -> verifyWithKey(didKey(did), message, signature);

	/**
	 * Verifier resolving {@code did:key} statelessly plus canonical numeric
	 * {@code did:convex:N} against the given CVM state: the signature must verify
	 * (match-any) against the account's currently authorised keys, so key rotation is
	 * implicit revocation. Named {@code did:convex} aliases require CNS resolution (CVM
	 * execution) and are not resolved here — pin the canonical account DID, or compose a
	 * caller-supplied verifier via {@link #or(DIDVerifier)}.
	 *
	 * @param state CVM state to resolve {@code did:convex} accounts against
	 * @return Verifier bound to a snapshot of the given state
	 */
	static DIDVerifier forState(State state) {
		if (state == null) return CONVEX;
		return (did, message, signature) -> {
			AccountKey key = didKey(did);
			if (key == null) key = convexAccountKey(state, did);
			return verifyWithKey(key, message, signature);
		};
	}

	/**
	 * Compose this verifier with a fallback: accept iff either accepts. A throwing
	 * left-hand verifier is treated as {@code false} so it cannot block the fallback.
	 *
	 * @param other Fallback verifier (null returns this verifier unchanged)
	 * @return Composed verifier
	 */
	default DIDVerifier or(DIDVerifier other) {
		if (other == null) return this;
		return (did, message, signature) -> {
			boolean first;
			try {
				first = this.verifies(did, message, signature);
			} catch (Throwable t) {
				first = false;
			}
			return first || other.verifies(did, message, signature);
		};
	}

	/**
	 * Decode the Ed25519 key from a {@code did:key} DID. Returns null for any other
	 * method or a malformed multikey.
	 */
	private static AccountKey didKey(AString did) {
		if (did == null) return null;
		String s = did.toString();
		if (!s.startsWith("did:key:")) return null;
		try {
			return Multikey.decodePublicKey(s.substring("did:key:".length()));
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Resolve a canonical numeric {@code did:convex:N} to the account's key in the given
	 * state. Returns null for named aliases, unknown accounts, or key-less (actor) accounts.
	 */
	private static AccountKey convexAccountKey(State state, AString did) {
		if (did == null) return null;
		String s = did.toString();
		if (!s.startsWith("did:convex:")) return null;
		try {
			Address addr = Address.parse(s.substring("did:convex:".length()));
			if (addr == null) return null; // named alias: needs CNS resolution, not resolvable here
			AccountStatus as = state.getAccount(addr);
			if (as == null) return null;
			// Match-any over the account's authorised key set (currently a single key)
			return as.getAccountKey();
		} catch (Exception e) {
			return null;
		}
	}

	private static boolean verifyWithKey(AccountKey key, Blob message, Blob signature) {
		if (key == null || message == null || signature == null) return false;
		try {
			ASignature sig = ASignature.fromBlob(signature);
			if (sig == null) return false;
			return sig.verify(message, key);
		} catch (Throwable t) {
			return false;
		}
	}
}
