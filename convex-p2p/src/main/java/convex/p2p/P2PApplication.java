package convex.p2p;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import convex.auth.did.DID;
import convex.auth.did.DIDKeyAuthorizer;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Sets;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;
import convex.lattice.ALattice;
import convex.lattice.ALatticeApplication;
import convex.lattice.IndexComponent;
import convex.lattice.LatticeApplication;
import convex.lattice.LatticeHost;
import convex.lattice.LocalLattice;
import convex.lattice.OwnedMap;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.generic.KeyedLattice;
import convex.social.Social;

/**
 * Root-level application component for the P2P lattice regions.
 *
 * <p>This is the application a P2P host hands incoming values to. It owns their
 * merge: an incoming root, or a path value reduced to its path, is projected to what
 * this node wants. The projection reduces the social region to the owners this node
 * wants and leaves out {@code :local}, which this node neither accepts nor
 * publishes. The lattice merge takes the regions in one update, verifying each
 * owner's slot; the node registry merges by this node's {@link NodeRegistryLattice},
 * which keeps only the node records this node wants, under the policy its
 * {@link P2PConnections} holds. The application also
 * owns the P2P policy that reads the lattice: the social owners this node wants and
 * the paths to pull from a newly admitted peer, with the node directory and contact
 * records held by its connections. It imports nothing from the node package. A
 * {@link P2PNode} assembles it with replication and calls it at the moments
 * replication knows about; a local application may use the same component over a
 * standalone {@link LatticeHost}.</p>
 *
 * <p>Every instance is bound to a host and has a directory and social policy.
 * Applications needing domain views or transaction drafts compose {@link Social}
 * and {@link P2PUser} over their own working cursor instead of inheriting this
 * node coordination component.</p>
 *
 * <p>The application publishes what it writes. When its policy edits local state,
 * such as this node's own record, it
 * syncs its host so the edit is persisted and announced.</p>
 */
public class P2PApplication extends LatticeApplication<Index<Keyword,ACell>> {

	private final LatticeHost<Index<Keyword,ACell>> host;
	private final SocialReplicationPolicy socialPolicy;
	private final AccountKey ownKey;
	private final BiFunction<ACell,ACell,SignedData<ACell>> signer;
	private final Supplier<CVMLong> clock;
	private final ASet<Keyword> servedRegions;
	private final P2PConnections connections;

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
		NodeRegistryLattice registry=new NodeRegistryLattice(ownKey);
		ALattice<?> region=lattice.path(P2PLattice.KEY_P2P);
		if (region instanceof KeyedLattice p2p && p2p.path(P2PLattice.KEY_NODES)!=null) {
			lattice=lattice.addLattice(P2PLattice.KEY_P2P,p2p.addLattice(P2PLattice.KEY_NODES,registry));
		}
		return new P2PApplication(lattice,host,ownKey,signer,clock,didAuthorizer,registry);
	}

	private P2PApplication(KeyedLattice lattice,LatticeHost<Index<Keyword,ACell>> host,
			AccountKey ownKey,BiFunction<ACell,ACell,SignedData<ACell>> signer,
			Supplier<CVMLong> clock,DIDKeyAuthorizer didAuthorizer,NodeRegistryLattice registry) {
		super(lattice,host.cursor());
		this.host=host;
		this.ownKey=ownKey;
		this.signer=signer;
		this.clock=java.util.Objects.requireNonNull(clock);
		this.servedRegions=Sets.fromCollection(lattice.getKeys()).exclude(LocalLattice.KEY_LOCAL);
		this.socialPolicy=new SocialReplicationPolicy(this,java.util.Objects.requireNonNull(didAuthorizer));
		this.connections=new P2PConnections(this,registry,signer);
	}

	AccountKey ownKey() { return ownKey; }
	Supplier<CVMLong> clock() { return clock; }

	/** This node's connection management: the node registry it keeps and its contact records. */
	P2PConnections connections() { return connections; }

	/** This node's contact records, or null for a node without a key. */
	ContactRecords contacts() { return connections.contacts(); }

	/** Syncs this node's connections, signing its {@code :local} slot once if its contact records changed. */
	@Override
	protected void syncChildren() {
		connections.sync();
	}

	/** The top-level regions this application serves, as advertised in its node record. Never {@code :local}. */
	ASet<Keyword> servedRegions() { return servedRegions; }

	/**
	 * Merges an incoming root projected to what this node wants ({@link #project})
	 * in one update, verifying each owner's slot. A path delivery arrives here
	 * reduced to its path, so the same projection selects it.
	 */
	@Override
	public Index<Keyword,ACell> merge(Index<Keyword,ACell> other) {
		Index<Keyword,ACell> wanted=project(other);
		if (wanted==null) return cursor().get();
		return cursor().updateAndGet(own -> getLattice().merge(own,wanted));
	}

	/**
	 * Projects a root to what this node exchanges with others: every region as
	 * declared except {@code :local}, which holds state that is not shared, and the
	 * social region reduced to the owners this node wants. Incoming roots merge
	 * through it and a node publishes through it.
	 */
	Index<Keyword,ACell> project(Index<Keyword,ACell> root) {
		Index<Keyword,ACell> wanted=socialPolicy.projectRoot(root);
		return (wanted==null)?null:wanted.dissoc(LocalLattice.KEY_LOCAL);
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
	 * {@link #merge}, which verifies the owner envelopes without editing local intent.
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
		return connections.directory();
	}

	/** This application's social retention and admission policy. */
	SocialReplicationPolicy policy() {
		return socialPolicy;
	}

	/**
	 * Syncs this application, then publishes the host root, so an edit made by this
	 * application's policy, and its contact records, are persisted and announced.
	 */
	void syncHost() {
		sync();
		host.sync();
	}

	/**
	 * Unsigned view at {@code [:id owner :value]}. Container components create missing
	 * ancestors only on a changed write and reject incompatible storage on access.
	 * The owned map binds local signing to the same rule as incoming delivery.
	 */
	static AForkableCursor<ACell> identitySlot(ALatticeApplication<Index<Keyword,ACell>> application, AccountKey userKey,
			BiFunction<ACell,ACell,SignedData<ACell>> signer) {
		var root=new IndexComponent<>(application.cursor());
		var ids=new OwnedMap<>(P2PLattice.ID_LATTICE,root.path(P2PLattice.KEY_ID));
		return ids.authoringCursor(userKey,value -> signer==null?null:signer.apply(userKey,value));
	}

	/** Cursor at {@code [:p2p :nodes owner :value]}, navigated and signed the same way. */
	static AForkableCursor<ACell> nodeSlot(ALatticeApplication<Index<Keyword,ACell>> application, AccountKey userKey,
			BiFunction<ACell,ACell,SignedData<ACell>> signer) {
		var root=new IndexComponent<>(application.cursor());
		var p2p=new IndexComponent<Keyword,ACell>(root.path(P2PLattice.KEY_P2P));
		var nodes=new OwnedMap<>(P2PLattice.NODES_LATTICE,p2p.path(P2PLattice.KEY_NODES));
		return nodes.authoringCursor(userKey,value -> signer==null?null:signer.apply(userKey,value));
	}
}
