package convex.auth.ucan;

import convex.auth.did.DID;
import convex.core.data.AString;
import convex.core.data.Strings;

/**
 * Predicate answering: may {@code rootIssuer} root a delegation grant over resource
 * {@code with}?
 *
 * <p>This is the trust-anchor decision for UCAN delegation chains: integrity checks prove
 * a chain is genuine and correctly linked, but only a root-authority policy says whose
 * signature at the root of the chain actually confers authority over a resource.</p>
 *
 * <p><b>Fail-closed contract:</b> a resource with no derivable owner (non-DID scheme,
 * malformed URI) yields {@code false} — the capability grants nothing, without rejecting
 * the token it appears in. Implementations should never throw; enforcement points wrap
 * calls so a defective policy is still treated as a denial.</p>
 */
@FunctionalInterface
public interface RootAuthorityPolicy {

	/**
	 * Check whether {@code rootIssuer} may root a grant over resource {@code with}.
	 *
	 * @param rootIssuer DID of the issuer of the chain's root token
	 * @param with Resource URI of the granted capability
	 * @return true iff a root signed by this issuer confers authority over the resource
	 */
	boolean acceptsRoot(AString rootIssuer, AString with);

	/**
	 * Self-sovereign policy: the owner of a DID-scoped resource is the DID that prefixes
	 * it, and only the owner may root a grant over it. DIDs are compared canonically
	 * (method + percent-decoded id), so encoding differences do not defeat or spoof the
	 * match. Any resource without a derivable DID owner is refused.
	 */
	RootAuthorityPolicy SELF_SOVEREIGN = (rootIssuer, with) -> {
		if (rootIssuer == null) return false;
		try {
			DID owner = ownerDID(with);
			if (owner == null) return false;
			return owner.equals(DID.fromString(rootIssuer.toString()));
		} catch (Exception e) {
			return false;
		}
	};

	/**
	 * Compose this policy with a fallback: accept iff either accepts. A throwing
	 * left-hand policy is treated as {@code false} so it cannot block the fallback.
	 *
	 * @param other Fallback policy (null returns this policy unchanged)
	 * @return Composed policy
	 */
	default RootAuthorityPolicy or(RootAuthorityPolicy other) {
		if (other == null) return this;
		return (rootIssuer, with) -> {
			boolean first;
			try {
				first = this.acceptsRoot(rootIssuer, with);
			} catch (Throwable t) {
				first = false;
			}
			return first || other.acceptsRoot(rootIssuer, with);
		};
	}

	/**
	 * Derive the owner DID of a DID-scoped resource URI: the base DID prefixing the
	 * resource path (e.g. {@code did:key:z6Mk.../w/notes} is owned by
	 * {@code did:key:z6Mk...}).
	 *
	 * @param with Resource URI, or null
	 * @return Owner DID, or null if the resource is not DID-scoped or is malformed
	 */
	static DID ownerDID(AString with) {
		if (with == null) return null;
		String s = with.toString();
		if (!s.startsWith("did:")) return null;
		try {
			return DID.fromString(s); // strips path/query/fragment, decodes percent-encoding
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Canonical owner DID of a DID-scoped resource as an AString, for callers that
	 * compare or index by owner. Null if no owner is derivable.
	 *
	 * @param with Resource URI, or null
	 * @return Canonical owner DID string, or null
	 */
	static AString resourceOwner(AString with) {
		DID d = ownerDID(with);
		return (d == null) ? null : Strings.create(d.toString());
	}
}
