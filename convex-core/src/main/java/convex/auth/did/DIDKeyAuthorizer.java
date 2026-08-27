package convex.auth.did;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import convex.core.cvm.AccountStatus;
import convex.core.cvm.Address;
import convex.core.cvm.State;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AccountKey;

/**
 * Resolves whether an {@link AccountKey} is currently authorised for a DID.
 *
 * <p>This is the shared key-resolution seam for lattice owner checks and
 * signature protocols. Implementations are expected to be bound to an
 * authenticated resolver snapshot and must fail closed for unsupported or stale
 * identities. Signature verification remains a separate, cached operation on
 * {@code SignedData}; this predicate answers only the DID-to-key binding.</p>
 */
@FunctionalInterface
public interface DIDKeyAuthorizer {

	/** Returns true iff {@code key} is authorised for the canonical base DID. */
	boolean authorises(AString did, AccountKey key);

	/** Stateless {@code did:key} authorisation. Other methods fail closed. */
	DIDKeyAuthorizer CONVEX = (did,key) -> {
		AccountKey expected=DID.keyFromDID(did);
		return expected!=null && expected.equals(key);
	};

	/**
	 * Resolves {@code did:key} plus canonical numeric {@code did:convex:N}
	 * against one authenticated CVM state snapshot.
	 */
	static DIDKeyAuthorizer forState(State state) {
		if (state==null) return CONVEX;
		return (did,key) -> {
			if (CONVEX.safeAuthorises(did,key)) return true;
			AccountKey accountKey=convexAccountKey(state,did);
			return accountKey!=null && accountKey.equals(key);
		};
	}

	/**
	 * Builds an authoriser from authenticated {@code alsoKnownAs} aliases.
	 * A {@code did:key} alias authorises exactly the key encoded by that alias;
	 * aliases never rewrite or equate the subject DID itself.
	 */
	static DIDKeyAuthorizer fromAlsoKnownAs(
			Function<AString,? extends Iterable<AString>> aliases) {
		if (aliases==null) return (did,key) -> false;
		return (did,key) -> {
			if (did==null || key==null) return false;
			try {
				Iterable<AString> values=aliases.apply(did);
				if (values==null) return false;
				for (AString alias:values) {
					AccountKey aliasKey=DID.keyFromDID(alias);
					if (aliasKey!=null && aliasKey.equals(key)) return true;
				}
			} catch (Throwable t) {
				return false;
			}
			return false;
		};
	}

	/** Composes two independent authenticated resolution mechanisms. */
	default DIDKeyAuthorizer or(DIDKeyAuthorizer other) {
		if (other==null) return this;
		return (did,key) -> safeAuthorises(did,key)||other.safeAuthorises(did,key);
	}

	/** Fail-closed invocation suitable for security boundaries. */
	default boolean safeAuthorises(AString did, AccountKey key) {
		try {
			return DID.isCanonicalBase(did) && key!=null && authorises(did,key);
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * Returns a bounded LRU cache around an immutable/snapshot resolver.
	 * Do not wrap a live rotating resolver without replacing the cache when its
	 * authenticated version changes.
	 */
	static DIDKeyAuthorizer cached(DIDKeyAuthorizer delegate, int maxEntries) {
		if (delegate==null) throw new IllegalArgumentException("Delegate must not be null");
		if (maxEntries<=0) throw new IllegalArgumentException("Cache size must be positive");
		record Binding(AString did,AccountKey key) {}
		Map<Binding,Boolean> cache=new LinkedHashMap<>(16,0.75f,true) {
			private static final long serialVersionUID=1L;
			@Override protected boolean removeEldestEntry(Map.Entry<Binding,Boolean> eldest) {
				return size()>maxEntries;
			}
		};
		return (did,key) -> {
			Binding binding=new Binding(did,key);
			synchronized (cache) {
				Boolean known=cache.get(binding);
				if (known!=null) return known;
			}
			boolean authorised=delegate.safeAuthorises(did,key);
			synchronized (cache) {
				cache.put(binding,authorised);
			}
			return authorised;
		};
	}

	/** Adapter for {@link convex.lattice.LatticeContext#withOwnerVerifier}. */
	default boolean verifiesOwner(ACell owner, AccountKey key) {
		return (owner instanceof AString did) && safeAuthorises(did,key);
	}

	static AccountKey convexAccountKey(State state, AString did) {
		if (!DID.isCanonicalBase(did)) return null;
		String s=did.toString();
		if (!s.startsWith("did:convex:")) return null;
		try {
			Address address=Address.parse(s.substring("did:convex:".length()));
			if (address==null) return null;
			AccountStatus account=state.getAccount(address);
			return (account==null)?null:account.getAccountKey();
		} catch (Exception e) {
			return null;
		}
	}
}
