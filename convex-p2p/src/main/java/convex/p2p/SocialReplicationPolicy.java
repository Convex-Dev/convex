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
import convex.node.NodeServer;
import convex.social.Follows;
import convex.social.Social;
import convex.social.SocialHelpers;

/** Default one-hop social retention and admission policy for a P2P node. */
final class SocialReplicationPolicy {

	private final NodeServer<Index<Keyword,ACell>> server;
	private final DIDKeyAuthorizer authorizer;
	private final Set<AString> localUsers=ConcurrentHashMap.newKeySet();
	private final ConcurrentHashMap<AString,AKeyPair> localSigners=
		new ConcurrentHashMap<>();
	private final Set<AString> pins=ConcurrentHashMap.newKeySet();
	private final ConcurrentHashMap<AString,AccountKey> validatedKeys=
		new ConcurrentHashMap<>();

	SocialReplicationPolicy(NodeServer<Index<Keyword,ACell>> server,
			DIDKeyAuthorizer authorizer) {
		this.server=server;
		this.authorizer=authorizer;
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
		ACell raw=server.getCursor().get(Social.KEY_SOCIAL);
		if (!(raw instanceof AHashMap<?,?> social)) return Set.copyOf(desired);
		@SuppressWarnings("unchecked")
		AHashMap<ACell,SignedData<Index<Keyword,ACell>>> typed=
			(AHashMap<ACell,SignedData<Index<Keyword,ACell>>>)social;
		desired.addAll(SocialHelpers.computeFollowSet(typed,localUsers));
		return Set.copyOf(desired);
	}

	ACell filterIngress(ACell[] path,ACell value) {
		return filter(path,value,true);
	}

	ACell filterPublication(ACell[] path,ACell value) {
		return filter(path,value,false);
	}

	private ACell filter(ACell[] path,ACell value,boolean cacheValidatedSigner) {
		Set<AString> desired=desiredOwners();
		if (path.length==0) return filterRoot(value,desired,cacheValidatedSigner);
		if (!Social.KEY_SOCIAL.equals(path[0])) return value;
		if (path.length==1) return filterOwners(value,desired,cacheValidatedSigner);
		if (path.length==2 && path[1] instanceof AString did && desired.contains(did)) {
			return validOwnerSlot(did,value,cacheValidatedSigner)?value:null;
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private ACell filterRoot(ACell value,Set<AString> desired,
			boolean cacheValidatedSigner) {
		if (!(value instanceof Index<?,?> rawRoot)) return null;
		Index<Keyword,ACell> root=(Index<Keyword,ACell>)rawRoot;
		ACell social=root.get(Social.KEY_SOCIAL);
		if (social==null) return root;
		ACell filtered=filterOwners(social,desired,cacheValidatedSigner);
		return root.assoc(Social.KEY_SOCIAL,
			(filtered==null)?Maps.empty():filtered);
	}

	@SuppressWarnings("unchecked")
	private ACell filterOwners(ACell value,Set<AString> desired,
			boolean cacheValidatedSigner) {
		if (!(value instanceof AHashMap<?,?> raw)) return null;
		AHashMap<ACell,ACell> owners=(AHashMap<ACell,ACell>)raw;
		AHashMap<ACell,ACell> filtered=Maps.empty();
		for (AString did:desired) {
			ACell slot=owners.get(did);
			if (slot!=null && validOwnerSlot(did,slot,cacheValidatedSigner)) {
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

	/** Persists the validated binding after an explicit desired-owner pull. */
	void cacheCurrentOwner(AString did) {
		ACell value=server.getCursor().get(Social.KEY_SOCIAL,did);
		if (value!=null) validOwnerSlot(did,value,true);
	}

	private void cacheFollowKey(AString target,AccountKey signer) {
		boolean changed=false;
		for (var entry:localSigners.entrySet()) {
			AString localDid=entry.getKey();
			Social social=Social.connect(server.getRootComponent(),
				convex.lattice.LatticeContext.create(
					null,entry.getValue(),authorizer::verifiesOwner));
			Follows follows=social.user(localDid).follows();
			AccountKey existing=follows.getCachedAccountKey(target);
			if (!follows.isFollowing(target)
					|| (existing!=null && signer.equals(existing))) continue;
			follows.cacheValidatedKey(target,signer);
			changed=true;
		}
		// Ingress may be a replay which does not otherwise change its target cursor.
		// Publish the authenticated cache edit explicitly in that case as well.
		if (changed) server.getCursor().sync();
	}

	private boolean hasCachedFollowKey(AString target,AccountKey signer) {
		Social social=Social.connect(server.getRootComponent());
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
