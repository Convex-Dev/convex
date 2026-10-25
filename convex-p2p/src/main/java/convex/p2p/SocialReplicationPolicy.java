package convex.p2p;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import convex.auth.did.DID;
import convex.auth.did.DIDKeyAuthorizer;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.social.Social;
import convex.social.SocialHelpers;

/**
 * Default one-hop social retention policy, owned by the P2P application. It reads
 * the application's lattice to decide which social owners this node wants and offers
 * that decision as a projection of the root: the application merges incoming values
 * through it, and a node publishes through it.
 */
final class SocialReplicationPolicy {

	private final P2PApplication application;
	private final DIDKeyAuthorizer authorizer;
	private final Social social;
	private final Set<AString> localUsers=ConcurrentHashMap.newKeySet();
	private final ConcurrentHashMap<AString,AKeyPair> localSigners=
		new ConcurrentHashMap<>();
	private final Set<AString> pins=ConcurrentHashMap.newKeySet();

	SocialReplicationPolicy(P2PApplication application,DIDKeyAuthorizer authorizer) {
		this.application=application;
		this.authorizer=authorizer;
		social=Social.connect(application.cursor().withDefault(Index.none()).path(Social.KEY_SOCIAL),authorizer,
			(owner,value) -> {
				AKeyPair key=localSigners.get(owner);
				return key==null?null:key.signData(value);
			},application.clock());
	}

	/** Shared multi-DID authoring view; key rotation is observed by existing user views. */
	Social social() { return social; }

	void addLocalUser(AString did,AKeyPair keyPair) {
		requireDID(did);
		if (keyPair==null) throw new IllegalArgumentException("Local signer must not be null");
		AccountKey signer=keyPair.getAccountKey();
		if (!authorizer.safeAuthorises(did,signer)) {
			throw new IllegalArgumentException("Signing key is not authorised for social DID "+did);
		}
		localUsers.add(did);
		localSigners.put(did,keyPair);
	}

	void pin(AString did) {
		requireDID(did);
		pins.add(did);
	}

	Set<AString> desiredOwners() {
		HashSet<AString> desired=new HashSet<>(localUsers);
		desired.addAll(pins);
		ACell raw=application.cursor().get(Social.KEY_SOCIAL);
		if (!(raw instanceof AHashMap<?,?> social)) return Set.copyOf(desired);
		@SuppressWarnings("unchecked")
		AHashMap<ACell,SignedData<Index<Keyword,ACell>>> typed=
			(AHashMap<ACell,SignedData<Index<Keyword,ACell>>>)social;
		desired.addAll(SocialHelpers.computeFollowSet(typed,localUsers));
		return Set.copyOf(desired);
	}

	/**
	 * Projects a root to what this node wants: every region as it is, the social
	 * region reduced to the wanted owners' valid slots.
	 */
	Index<Keyword,ACell> projectRoot(Index<Keyword,ACell> root) {
		if (root==null) return null;
		ACell social=root.get(Social.KEY_SOCIAL);
		if (social==null) return root;
		ACell filtered=filterOwners(social,desiredOwners());
		return root.assoc(Social.KEY_SOCIAL,(filtered==null)?Maps.empty():filtered);
	}

	@SuppressWarnings("unchecked")
	private ACell filterOwners(ACell value,Set<AString> desired) {
		if (!(value instanceof AHashMap<?,?> raw)) return null;
		AHashMap<ACell,ACell> owners=(AHashMap<ACell,ACell>)raw;
		AHashMap<ACell,ACell> filtered=Maps.empty();
		for (AString did:desired) {
			ACell slot=owners.get(did);
			if (slot!=null && validOwnerSlot(did,slot)) {
				filtered=filtered.assoc(did,slot);
			}
		}
		if (!owners.isEmpty() && filtered.isEmpty()) return null;
		return filtered;
	}

	private boolean validOwnerSlot(AString did,ACell value) {
		if (!(value instanceof SignedData<?> signed) || !signed.checkSignature()) return false;
		// Relaying an owner's envelope does not require understanding all its fields.
		if (!(signed.getValue() instanceof Index<?,?>)) return false;
		AccountKey signer=signed.getAccountKey();
		return authorizer.safeAuthorises(did,signer);
	}

	private static void requireDID(AString did) {
		if (!DID.isCanonicalBase(did)) {
			throw new IllegalArgumentException("Expected canonical base DID");
		}
	}
}
