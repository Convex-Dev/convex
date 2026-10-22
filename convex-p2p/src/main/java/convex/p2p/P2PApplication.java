package convex.p2p;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

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
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;
import convex.lattice.ALattice;
import convex.lattice.ALatticeApplication;
import convex.lattice.LatticeApplication;
import convex.lattice.LatticeHost;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.SignedCursor;
import convex.lattice.generic.KeyedLattice;
import convex.social.Social;

/**
 * Root-level application component for the P2P lattice regions.
 *
 * <p>This is the application a P2P host hands incoming values to. It owns their
 * merge: an incoming root, or a path value reduced to its path, is projected to what
 * this node wants and delegated to the lattice merge, which merges every region as
 * declared and verifies each owner's slot. The projection keeps the infrastructure
 * regions whole and reduces the social region to the owners this node wants. The
 * application also owns the P2P policy that reads the lattice:
 * the node registry as a validated directory, the social owners this node wants, and
 * the paths to pull from a newly admitted peer. It imports nothing from the node
 * package. A {@link P2PNode} assembles it with replication and calls it at the
 * moments replication knows about; a local application may use the same component
 * over a standalone {@link LatticeHost}.</p>
 *
 * <p>The application publishes what it writes. When its policy edits local state,
 * such as this node's own record or a follow key validated during admission, it
 * syncs its host so the edit is persisted and announced.</p>
 */
public class P2PApplication extends LatticeApplication<Index<Keyword,ACell>> {

	private final LatticeHost<Index<Keyword,ACell>> host;
	private final NodeDirectory directory;
	private final SocialReplicationPolicy socialPolicy;
	private final AccountKey ownKey;
	private final BiFunction<ACell,ACell,SignedData<ACell>> signer;
	private final Supplier<CVMLong> clock;

	/** Connects a relay with standard DID verification and no authoring capability. */
	public static P2PApplication connect(KeyedLattice lattice,LatticeHost<Index<Keyword,ACell>> host) {
		return connect(lattice,host,null,null,Utils::getCurrentTimestampCell,DIDKeyAuthorizer.CONVEX);
	}

	/**
	 * Supplies node identity, multi-owner signing, time and DID admission explicitly.
	 * The supplied root selects served regions. Its social owner boundary is
	 * bound to this same DID authoriser. Root delivery is a relay:
	 * domain components own any synthesis requiring a fresh signature.
	 */
	public static P2PApplication connect(KeyedLattice lattice,LatticeHost<Index<Keyword,ACell>> host,
			AccountKey ownKey,BiFunction<ACell,ACell,SignedData<ACell>> signer,
			Supplier<CVMLong> clock,DIDKeyAuthorizer didAuthorizer) {
		if (lattice.path(Social.KEY_SOCIAL)!=null) {
			lattice=lattice.addLattice(Social.KEY_SOCIAL,Social.ownerLattice(didAuthorizer));
		}
		return new P2PApplication(lattice,host,ownKey,signer,clock,didAuthorizer);
	}

	private P2PApplication(ALattice<Index<Keyword,ACell>> lattice,LatticeHost<Index<Keyword,ACell>> host,
			AccountKey ownKey,BiFunction<ACell,ACell,SignedData<ACell>> signer,
			Supplier<CVMLong> clock,DIDKeyAuthorizer didAuthorizer) {
		super(lattice,host.cursor());
		this.host=host;
		this.ownKey=ownKey;
		this.signer=signer;
		this.clock=java.util.Objects.requireNonNull(clock);
		this.directory=new NodeDirectory(this);
		this.socialPolicy=new SocialReplicationPolicy(this,java.util.Objects.requireNonNull(didAuthorizer));
	}

	/** Binds a working view, including a transaction draft, with an explicit signer. */
	protected P2PApplication(ALattice<Index<Keyword,ACell>> lattice,AForkableCursor<Index<Keyword,ACell>> cursor,
			BiFunction<ACell,ACell,SignedData<ACell>> signer,Supplier<CVMLong> clock) {
		super(lattice,cursor);
		this.host=null;
		this.directory=null;
		this.socialPolicy=null;
		this.ownKey=null;
		this.signer=signer;
		this.clock=java.util.Objects.requireNonNull(clock);
	}

	AccountKey ownKey() { return ownKey; }
	Supplier<CVMLong> clock() { return clock; }

	/**
	 * Merges an incoming root projected to what this node wants: every region as
	 * declared, the social region reduced to the owners this node wants. The lattice
	 * merge does the rest, verifying each owner's slot, and the validated signer of
	 * each wanted owner whose slot arrived is then recorded. A path delivery arrives
	 * here reduced to its path, so the same projection selects it. A working view
	 * bound to a cursor has no policy and so no interest of its own; it delegates whole.
	 */
	@Override
	public Index<Keyword,ACell> merge(Index<Keyword,ACell> other) {
		Index<Keyword,ACell> wanted=(socialPolicy==null)?other:socialPolicy.projectRoot(other);
		Index<Keyword,ACell> result=cursor().updateAndGet(own -> getLattice().merge(own,wanted));
		if (socialPolicy!=null) socialPolicy.recordOwners(wanted);
		return result;
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
		return policy.social();
	}

	/** Retains an additional social DID independently of local follow lists. */
	public void pinSocial(AString did) {
		policy().pin(did);
	}

	/** Returns the component representing one P2P user's owned regions. */
	public P2PUser p2p(AccountKey userKey) {
		return P2PUser.create(this,userKey,signer);
	}

	/** Returns the component at one user's identity slot, wrapped in its signing boundary. */
	public P2PIdentity identity(AccountKey userKey) {
		if (userKey==null) throw new IllegalArgumentException("User key must not be null");
		return new P2PIdentity(identitySlot(this,userKey,signer),userKey);
	}

	/** Returns the component at one user's node-record slot, wrapped in its signing boundary. */
	public P2PNodeRecord node(AccountKey userKey) {
		if (userKey==null) throw new IllegalArgumentException("User key must not be null");
		return new P2PNodeRecord(nodeSlot(this,userKey,signer),userKey);
	}

	/**
	 * Paths a node pulls from a newly admitted peer, in order: the infrastructure
	 * regions, then each currently wanted social owner's slot. Each arrives through
	 * {@link #merge}, which records validated signers itself.
	 */
	List<ACell[]> bootstrapPaths() {
		List<ACell[]> paths=new ArrayList<>();
		paths.add(new ACell[] {P2PLattice.KEY_P2P});
		paths.add(new ACell[] {P2PLattice.KEY_ID});
		for (AString did:policy().desiredOwners()) paths.add(new ACell[] {Social.KEY_SOCIAL,did});
		return paths;
	}

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
	 * {@code :id} level through the known index root; this component owns that level,
	 * so it wraps the owner's slot in the signing boundary bound to the
	 * application's policy.
	 */
	static AForkableCursor<ACell> identitySlot(ALatticeApplication<Index<Keyword,ACell>> application, AccountKey userKey,
			BiFunction<ACell,ACell,SignedData<ACell>> signer) {
		AForkableCursor<AHashMap<ACell,SignedData<ACell>>> ids=application.cursor().withDefault(Index.none()).path(P2PLattice.KEY_ID);
		var rule=P2PLattice.ID_LATTICE.signingFor(userKey);
		return SignedCursor.create(ids.withDefault(Maps.empty()).path(userKey),v -> signer==null?null:rule.sign(v,value -> signer.apply(userKey,value)));
	}

	/** Cursor at {@code [:p2p :nodes owner :value]}, navigated and signed the same way. */
	static AForkableCursor<ACell> nodeSlot(ALatticeApplication<Index<Keyword,ACell>> application, AccountKey userKey,
			BiFunction<ACell,ACell,SignedData<ACell>> signer) {
		AForkableCursor<AHashMap<ACell,SignedData<ACell>>> nodes=
			application.cursor().withDefault(Index.none()).<Index<Keyword,ACell>>path(P2PLattice.KEY_P2P)
				.withDefault(Index.none()).path(P2PLattice.KEY_NODES);
		var rule=P2PLattice.NODES_LATTICE.signingFor(userKey);
		return SignedCursor.create(nodes.withDefault(Maps.empty()).path(userKey),v -> signer==null?null:rule.sign(v,value -> signer.apply(userKey,value)));
	}
}
