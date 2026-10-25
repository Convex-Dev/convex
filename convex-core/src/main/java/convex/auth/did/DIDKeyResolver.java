package convex.auth.did;

import convex.core.cvm.State;
import convex.core.data.AString;
import convex.core.data.AccountKey;

/**
 * Resolves the account key to encrypt to for a DID: the companion of
 * {@link DIDKeyAuthorizer}, which only answers whether a given key is authorised.
 *
 * <p>{@code did:key} is resolved inline. Other methods are pluggable and must return
 * only an authenticated key, or null.</p>
 */
@FunctionalInterface
public interface DIDKeyResolver {

	/** Returns the key to encrypt to for the canonical base DID, or null if none is known. */
	AccountKey resolve(AString did);

	/** Stateless {@code did:key} resolution. Other methods resolve to null. */
	DIDKeyResolver CONVEX=DID::keyFromDID;

	/** Resolves {@code did:key} plus numeric {@code did:convex:N} against one state snapshot. */
	static DIDKeyResolver forState(State state) {
		if (state==null) return CONVEX;
		return did -> {
			AccountKey key=DID.keyFromDID(did);
			return (key!=null)?key:DIDKeyAuthorizer.convexAccountKey(state,did);
		};
	}
}
