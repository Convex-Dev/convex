package convex.net;

import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;

/**
 * A lattice peer as {@link PeerResolver} resolves it from its name: the keys it may
 * prove and the endpoints to dial it at.
 *
 * @param name the peer's name as the operator wrote it, for messages
 * @param keys keys the peer may prove; empty if it is admitted under whatever key it proves
 * @param endpoints transport URIs at which the peer may be dialled, in order of preference
 */
public record PeerTarget(AString name,AVector<AccountKey> keys,AVector<AString> endpoints) {

	public PeerTarget {
		if (name==null || keys==null || endpoints==null) {
			throw new IllegalArgumentException("A peer target needs a name, keys and endpoints");
		}
	}

	/**
	 * Whether a peer that proved a key may be admitted as this target.
	 *
	 * @param key key the peer proved
	 * @return true if no keys are named or the key is one of them
	 */
	public boolean admits(AccountKey key) {
		return (key!=null) && (keys.isEmpty() || keys.contains(key));
	}

	@Override
	public String toString() {
		return name.toString();
	}
}
