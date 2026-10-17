package convex.p2p;

import java.util.ArrayList;
import java.util.List;

import convex.auth.did.DID;
import convex.auth.did.DIDKeyAuthorizer;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.SignedData;
import convex.lattice.ALattice;
import convex.lattice.ALatticeApplication;
import convex.lattice.LatticeContext;
import convex.lattice.LatticeHost;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.SignedCursor;
import convex.social.Social;
import convex.social.SocialLattice;

/**
 * Root-level application component for the P2P lattice regions.
 *
 * <p>This is the application a P2P host hands incoming values to. It decides at
 * which paths to call the default merge: the infrastructure regions it takes whole,
 * through their declared lattices, and from the social region it takes only the
 * slots of the owners this node wants, crossing each with the owner lattice that
 * verifies it. It also owns the P2P policy that reads the lattice: the node registry
 * as a validated directory, the social owners this node wants, and the paths to pull
 * from a newly admitted peer. It imports nothing from the node package. A
 * {@link P2PNode} assembles it with replication and calls it at the moments
 * replication knows about; a local application may use the same component over a
 * standalone {@link LatticeHost}.</p>
 *
 * <p>The application publishes what it writes. When its policy edits local state,
 * such as this node's own record or a follow key validated during admission, it
 * syncs its host so the edit is persisted and announced.</p>
 */
public class P2PApplication extends ALatticeApplication<Index<Keyword,ACell>> {

	private final LatticeHost<Index<Keyword,ACell>> host;
	private final NodeDirectory directory;
	private final SocialReplicationPolicy socialPolicy;

	/** Live over the host root under the host's context, with the default DID resolution policy. */
	protected P2PApplication(ALattice<Index<Keyword,ACell>> lattice,LatticeHost<Index<Keyword,ACell>> host) {
		this(lattice,host,host.getContext(),DIDKeyAuthorizer.CONVEX);
	}

	/** Live over the host root, under an explicit write and merge policy. */
	protected P2PApplication(ALattice<Index<Keyword,ACell>> lattice,LatticeHost<Index<Keyword,ACell>> host,
			LatticeContext context,DIDKeyAuthorizer didAuthorizer) {
		super(lattice,host.cursor());
		if (didAuthorizer==null) throw new IllegalArgumentException("DID authorizer must not be null");
		setContext((context==null)?LatticeContext.EMPTY:context);
		this.host=host;
		this.directory=new NodeDirectory(this);
		this.socialPolicy=new SocialReplicationPolicy(this,didAuthorizer);
	}

	/** Binds a working view, such as a fork or transaction draft, to the supplied cursor. */
	protected P2PApplication(ALattice<Index<Keyword,ACell>> lattice, AForkableCursor<Index<Keyword,ACell>> cursor) {
		super(lattice,cursor);
		this.host=null;
		this.directory=null;
		this.socialPolicy=null;
	}

	/** Connects a P2P application to a hosted lattice root under the host's context. */
	public static P2PApplication connect(ALattice<Index<Keyword,ACell>> lattice,LatticeHost<Index<Keyword,ACell>> host) {
		return new P2PApplication(lattice,host);
	}

	/** Connects a P2P application with its own write policy and DID resolution policy. */
	public static P2PApplication connect(ALattice<Index<Keyword,ACell>> lattice,LatticeHost<Index<Keyword,ACell>> host,
			LatticeContext context,DIDKeyAuthorizer didAuthorizer) {
		return new P2PApplication(lattice,host,context,didAuthorizer);
	}

	/**
	 * Takes an incoming root selectively: every declared region other than social
	 * whole, through its declared lattice, and from the social region only the slots
	 * of the owners this node wants. A working view bound to a cursor has no policy
	 * and so no interest of its own; it delegates to the declared lattice.
	 */
	@Override
	public Index<Keyword,ACell> merge(Index<Keyword,ACell> other) {
		if (socialPolicy==null) return super.merge(other);
		if (other==null) return cursor.get();
		for (var entry:other.entrySet()) {
			ACell key=entry.getKey();
			if (Social.KEY_SOCIAL.equals(key)) mergeSocialOwners(entry.getValue());
			else if (getLattice().path(key)!=null) mergeDeclaredPath(entry.getValue(),key);
		}
		return cursor.get();
	}

	/**
	 * Accepts declared paths outside the social region whole. A social owner map is
	 * taken for the desired owners only, a social slot only if its owner is desired,
	 * and nothing inside an owner's envelope.
	 */
	@SuppressWarnings("unchecked")
	@Override
	public Index<Keyword,ACell> mergeAt(ACell value,ACell... path) {
		if (socialPolicy==null) return mergeDeclaredPath(value,path);
		if (path.length==0) return merge((Index<Keyword,ACell>)value);
		if (!Social.KEY_SOCIAL.equals(path[0])) return mergeDeclaredPath(value,path);
		if (path.length==1) return mergeSocialOwners(value);
		if (path.length==2 && path[1] instanceof AString did && policy().desiredOwners().contains(did)) {
			return mergeDesiredOwner(did,value);
		}
		return cursor.get();
	}

	/** Takes the desired owners' slots from an owner map, walking the desired set rather than the peer's map. */
	@SuppressWarnings("unchecked")
	private Index<Keyword,ACell> mergeSocialOwners(ACell value) {
		if (value instanceof AMap<?,?> owners) {
			for (AString did:policy().desiredOwners()) {
				ACell slot=((AMap<ACell,ACell>)owners).get(did);
				if (slot!=null) mergeDesiredOwner(did,slot);
			}
		}
		return cursor.get();
	}

	/**
	 * Merges one wanted owner's slot through the owner lattice, which verifies its
	 * signer, then records the validated signer. The inner state must already be in
	 * canonical shape: an envelope adopted into an empty slot is taken as it is.
	 */
	private Index<Keyword,ACell> mergeDesiredOwner(AString did,ACell slot) {
		if (!(slot instanceof SignedData<?> signed) || !SocialLattice.isCanonicalState(signed.getValue())) {
			return cursor.get();
		}
		mergeDeclaredPath(slot,Social.KEY_SOCIAL,did);
		policy().cacheCurrentOwner(did);
		return cursor.get();
	}

	/** Registers and opens a local {@code did:key} social user. */
	public Social social(AKeyPair userKeyPair) {
		if (userKeyPair==null) throw new IllegalArgumentException("User key pair must not be null");
		return social(DID.forKey(userKeyPair.getAccountKey()),userKeyPair);
	}

	/** Registers a local stable DID with one currently authorised signing key and opens it. */
	public Social social(AString did,AKeyPair userKeyPair) {
		if (userKeyPair==null) throw new IllegalArgumentException("User key pair must not be null");
		SocialReplicationPolicy policy=policy();
		policy.addLocalUser(did,userKeyPair);
		return Social.connect(this,LatticeContext.create(
			null,userKeyPair,policy.authorizer()::verifiesOwner));
	}

	/** Retains an additional social DID independently of local follow lists. */
	public void pinSocial(AString did) {
		policy().pin(did);
	}

	/** Returns the component representing one P2P user's owned regions. */
	public P2PUser p2p(AccountKey userKey) {
		return P2PUser.create(this,userKey);
	}

	/** Returns the component at one user's identity slot, wrapped in its signing boundary. */
	public P2PIdentity identity(AccountKey userKey) {
		if (userKey==null) throw new IllegalArgumentException("User key must not be null");
		return configured(new P2PIdentity(identitySlot(this,userKey),userKey));
	}

	/** Returns the component at one user's node-record slot, wrapped in its signing boundary. */
	public P2PNodeRecord node(AccountKey userKey) {
		if (userKey==null) throw new IllegalArgumentException("User key must not be null");
		return configured(new P2PNodeRecord(nodeSlot(this,userKey),userKey));
	}

	/**
	 * Paths a node pulls from a newly admitted peer, in order: the infrastructure
	 * regions, then each currently desired complete social-owner slot. Each step
	 * names what the application does once that path has merged.
	 */
	List<BootstrapStep> bootstrapSteps() {
		SocialReplicationPolicy policy=policy();
		List<BootstrapStep> steps=new ArrayList<>();
		steps.add(new BootstrapStep(new ACell[] {P2PLattice.KEY_P2P},() -> {}));
		steps.add(new BootstrapStep(new ACell[] {P2PLattice.KEY_ID},() -> {}));
		for (AString did:policy.desiredOwners()) {
			steps.add(new BootstrapStep(new ACell[] {Social.KEY_SOCIAL,did},() -> policy.cacheCurrentOwner(did)));
		}
		return steps;
	}

	/** One bootstrap pull: a path within the root and the application's step once it has merged. */
	record BootstrapStep(ACell[] path,Runnable afterMerge) {}

	/** This application's validated view of the node registry. */
	NodeDirectory directory() {
		if (directory==null) throw new IllegalStateException("A working view has no node directory");
		return directory;
	}

	/** This application's social retention and admission policy. */
	SocialReplicationPolicy policy() {
		if (socialPolicy==null) throw new IllegalStateException("A working view has no social policy");
		return socialPolicy;
	}

	/** Publishes the host root, so an edit made by this application's policy is persisted and announced. */
	void syncHost() {
		if (host!=null) host.sync();
	}

	/**
	 * Cursor at {@code [:id owner :value]}. The application navigates to the
	 * {@code :id} level with its own root lattice; this component owns that level,
	 * so it wraps the owner's slot in the signing boundary bound to the
	 * application's policy.
	 */
	static AForkableCursor<ACell> identitySlot(ALatticeApplication<?> application, AccountKey userKey) {
		AForkableCursor<AHashMap<ACell,SignedData<ACell>>> ids=application.resolve(P2PLattice.KEY_ID);
		var rule=P2PLattice.ID_LATTICE.signingFor(userKey);
		LatticeContext context=application.getContext();
		return SignedCursor.create(resolve(ids,P2PLattice.ID_LATTICE,userKey),v -> rule.sign(context,v));
	}

	/** Cursor at {@code [:p2p :nodes owner :value]}, navigated and signed the same way. */
	static AForkableCursor<ACell> nodeSlot(ALatticeApplication<?> application, AccountKey userKey) {
		AForkableCursor<AHashMap<ACell,SignedData<ACell>>> nodes=
			application.resolve(P2PLattice.KEY_P2P,P2PLattice.KEY_NODES);
		var rule=P2PLattice.NODES_LATTICE.signingFor(userKey);
		LatticeContext context=application.getContext();
		return SignedCursor.create(resolve(nodes,P2PLattice.NODES_LATTICE,userKey),v -> rule.sign(context,v));
	}
}
