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
import convex.social.Follows;
import convex.social.Social;
import convex.social.SocialHelpers;

/**
 * Default one-hop social retention policy, owned by the P2P application. It reads
 * the application's lattice to decide which social owners this node wants. The
 * application applies that decision to incoming values itself; the policy offers it
 * as the publication projection a node plugs into its propagation group.
 */
final class SocialReplicationPolicy {

	private final P2PApplication application;
	private final DIDKeyAuthorizer authorizer;
	private final Set<AString> localUsers=ConcurrentHashMap.newKeySet();
	private final ConcurrentHashMap<AString,AKeyPair> localSigners=
		new ConcurrentHashMap<>();
	private final Set<AString> pins=ConcurrentHashMap.newKeySet();
	private final ConcurrentHashMap<AString,AccountKey> validatedKeys=
		new ConcurrentHashMap<>();

	SocialReplicationPolicy(P2PApplication application,DIDKeyAuthorizer authorizer) {
		this.application=application;
		this.authorizer=authorizer;
	}

	/** The DID resolution policy social admission verifies against. */
	DIDKeyAuthorizer authorizer() {
		return authorizer;
	}

	void addLocalUser(AString did,AKeyPair keyPair) {
		requireDID(did);
		if (keyPair==null) throw new IllegalArgumentException("Local signer must not be null");
		AccountKey signer=keyPair.getAccountKey();
		if (!authorizer.safeAuthorises(did,signer)) {
			throw new IllegalArgumentException("Signing key is not authorised for social DID "+did);
		}
		localUsers.add(did);
		localSigners.put(did,keyPair);
		validatedKeys.put(did,signer);
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

	/** Projects a published value down to the desired owners' valid slots. */
	ACell filterPublication(ACell[] path,ACell value) {
		Set<AString> desired=desiredOwners();
		if (path.length==0) return filterRoot(value,desired);
		if (!Social.KEY_SOCIAL.equals(path[0])) return value;
		if (path.length==1) return filterOwners(value,desired);
		if (path.length==2 && path[1] instanceof AString did && desired.contains(did)) {
			return validOwnerSlot(did,value,false)?value:null;
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private ACell filterRoot(ACell value,Set<AString> desired) {
		if (!(value instanceof Index<?,?> rawRoot)) return null;
		Index<Keyword,ACell> root=(Index<Keyword,ACell>)rawRoot;
		ACell social=root.get(Social.KEY_SOCIAL);
		if (social==null) return root;
		ACell filtered=filterOwners(social,desired);
		return root.assoc(Social.KEY_SOCIAL,
			(filtered==null)?Maps.empty():filtered);
	}

	@SuppressWarnings("unchecked")
	private ACell filterOwners(ACell value,Set<AString> desired) {
		if (!(value instanceof AHashMap<?,?> raw)) return null;
		AHashMap<ACell,ACell> owners=(AHashMap<ACell,ACell>)raw;
		AHashMap<ACell,ACell> filtered=Maps.empty();
		for (AString did:desired) {
			ACell slot=owners.get(did);
			if (slot!=null && validOwnerSlot(did,slot,false)) {
				filtered=filtered.assoc(did,slot);
			}
		}
		if (!owners.isEmpty() && filtered.isEmpty()) return null;
		return filtered;
	}

	private boolean validOwnerSlot(AString did,ACell value,
			boolean cacheValidatedSigner) {
		if (!(value instanceof SignedData<?> signed) || !signed.checkSignature()) return false;
		if (!convex.social.SocialLattice.isCanonicalState(signed.getValue())) return false;
		AccountKey signer=signed.getAccountKey();
		AccountKey cached=validatedKeys.get(did);
		if (cached!=null && signer.equals(cached)) {
			if (cacheValidatedSigner) cacheFollowKey(did,signer);
			return true;
		}
		if (hasCachedFollowKey(did,signer)) {
			validatedKeys.put(did,signer);
			return true;
		}
		if (!authorizer.safeAuthorises(did,signer)) return false;
		if (cacheValidatedSigner) cacheFollowKey(did,signer);
		validatedKeys.put(did,signer);
		return true;
	}

	/** Records the validated signer of a desired owner's slot after it has merged. */
	void cacheCurrentOwner(AString did) {
		ACell value=application.cursor().get(Social.KEY_SOCIAL,did);
		if (value!=null) validOwnerSlot(did,value,true);
	}

	private void cacheFollowKey(AString target,AccountKey signer) {
		boolean changed=false;
		for (var entry:localSigners.entrySet()) {
			AString localDid=entry.getKey();
			Social social=Social.connect(application,
				convex.lattice.LatticeContext.create(
					null,entry.getValue(),authorizer::verifiesOwner));
			Follows follows=social.user(localDid).follows();
			AccountKey existing=follows.getCachedAccountKey(target);
			if (!follows.isFollowing(target)
					|| (existing!=null && signer.equals(existing))) continue;
			follows.cacheValidatedKey(target,signer);
			changed=true;
		}
		// A delivery may be a replay which does not otherwise change its target cursor, so
		// the node's post-merge sync would not run: the application publishes its edit.
		if (changed) application.syncHost();
	}

	private boolean hasCachedFollowKey(AString target,AccountKey signer) {
		Social social=Social.connect(application);
		for (AString local:localUsers) {
			Follows follows=social.user(local).follows();
			if (!follows.isFollowing(target)) continue;
			AccountKey cached=follows.getCachedAccountKey(target);
			if (cached!=null && signer.equals(cached)) return true;
		}
		return false;
	}

	private static void requireDID(AString did) {
		if (!DID.isCanonicalBase(did)) {
			throw new IllegalArgumentException("Expected canonical base DID");
		}
	}
}
