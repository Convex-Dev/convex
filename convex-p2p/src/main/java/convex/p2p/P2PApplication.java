package convex.p2p;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import convex.auth.did.DID;
import convex.auth.did.DIDKeyAuthorizer;
import convex.core.crypto.AKeyPair;
import convex.core.data.ABlobLike;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Sets;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;
import convex.lattice.ALatticeApplication;
import convex.lattice.ALatticeComponent;
import convex.lattice.IndexComponent;
import convex.lattice.LatticeHost;
import convex.lattice.LatticeOps;
import convex.lattice.LocalLattice;
import convex.lattice.MapComponent;
import convex.lattice.OwnedMap;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Root;
import convex.lattice.generic.KeyedLattice;
import convex.social.Social;

/**
 * Root-level application component for the P2P lattice regions.
 *
 * <p>This is the application a P2P host hands incoming values to. It owns their
 * merge: an incoming root, or a path value reduced to its path, is projected to what
 * this node wants. The projection reduces the social region to the owners this node
 * wants and leaves out {@code :local}, which this node neither accepts nor
 * publishes. Its {@link NodeRegistry} merges the {@code :p2p} region and keeps the
 * node records this node wants; the lattice merge takes the other regions as
 * declared, verifying each owner's slot. The two merge in separate updates, as
 * keyed regions do, so a reader may see one before the other. The application also
 * owns the P2P policy that reads the lattice: the social owners this node wants and
 * the paths to pull from a newly admitted peer.</p>
 *
 * <p>It assembles each region's components side by side: the registry at
 * {@code [:p2p]} with its validated view, the {@link NodeDirectory}, and this node's
 * record at {@code [:local <node key> :p2p]}, the {@link ContactRecords} holding the
 * {@link Blocklist} and the {@link Seeds}. The registry reads the local record only through what the
 * application supplies to it. It imports nothing from the node package. A
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
public class P2PApplication extends ALatticeApplication<Index<Keyword,ACell>> {

	private final KeyedLattice lattice;
	private final LatticeHost<Index<Keyword,ACell>> host;
	private final SocialReplicationPolicy socialPolicy;
	private final AccountKey ownKey;
	private final BiFunction<ACell,ACell,SignedData<ACell>> signer;
	private final Supplier<CVMLong> clock;
	private final ASet<Keyword> servedRegions;
	private final NodeDirectory directory;
	private final ContactRecords contacts;
	private final Blocklist blocklist;
	private final Seeds seeds;
	private final NodeRegistry registry;

	/** Connects a relay with standard DID verification and no authoring capability. */
	public static P2PApplication connect(KeyedLattice lattice,LatticeHost<Index<Keyword,ACell>> host) {
		return connect(lattice,host,null,null,Utils::getCurrentTimestampCell,DIDKeyAuthorizer.CONVEX);
	}

	/**
	 * Supplies node identity, multi-owner signing, time and DID admission explicitly.
	 * The supplied root selects the regions it merges. Its social owner boundary is
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

	@SuppressWarnings("unchecked")
	private P2PApplication(KeyedLattice lattice,LatticeHost<Index<Keyword,ACell>> host,
			AccountKey ownKey,BiFunction<ACell,ACell,SignedData<ACell>> signer,
			Supplier<CVMLong> clock,DIDKeyAuthorizer didAuthorizer) {
		super(host.cursor());
		this.lattice=lattice;
		this.host=host;
		this.ownKey=ownKey;
		this.signer=signer;
		this.clock=java.util.Objects.requireNonNull(clock);
		this.servedRegions=Sets.fromCollection(lattice.getKeys()).exclude(LocalLattice.KEY_LOCAL);
		this.socialPolicy=new SocialReplicationPolicy(this,java.util.Objects.requireNonNull(didAuthorizer));
		this.directory=new NodeDirectory(this);
		// Only a node that can sign its own :local slot keeps contact records
		this.contacts=(ownKey==null || signer==null)?null:new ContactRecords(cursor(),ownKey,
			value -> (SignedData<AHashMap<Keyword,ACell>>)(SignedData<?>)signer.apply(ownKey,value),clock);
		// The blocklist and seed list share the contact records' :local record
		this.blocklist=new Blocklist(this.<Index<ABlobLike<?>,ACell>>localField(Blocklist.KEY),clock);
		this.seeds=new Seeds(this.<Index<AString,ACell>>localField(Seeds.KEY),clock);
		// A node without contact records has reached no other node
		Supplier<ToIntFunction<AccountKey>> standings=(contacts==null)
			?() -> node -> ContactRecords.standing(null):contacts::standings;
		this.registry=new NodeRegistry(new IndexComponent<Keyword,ACell>(cursor()).<Index<Keyword,ACell>>path(P2PLattice.KEY_P2P),
			ownKey,blocklist::isBlocked,standings);
	}

	/** A field of this node's {@code :local} P2P record, or memory of its own for a node without that record. */
	private <T extends ACell> AForkableCursor<T> localField(Keyword field) {
		if (contacts==null) return new Root<T>();
		return new MapComponent<Keyword,ACell>(contacts.cursor()).<T>path(field);
	}

	AccountKey ownKey() { return ownKey; }
	Supplier<CVMLong> clock() { return clock; }

	/** This node's node registry, the component at {@code [:p2p]}. */
	NodeRegistry registry() { return registry; }

	/** This node's contact records, or null for a node without a key. */
	ContactRecords contacts() { return contacts; }

	/** The nodes and addresses this node refuses. */
	Blocklist blocklist() { return blocklist; }

	/** The seed nodes this node joins the network through. */
	Seeds seeds() { return seeds; }

	/**
	 * Blocks a node: refuses it from now on and drops its record. Nothing is synced.
	 * Returns whether it was not blocked before.
	 */
	boolean block(AccountKey node) {
		boolean added=blocklist.block(node);
		registry.drop(node);
		directory.refresh();
		return added;
	}

	/** Syncs this node's contact records and blocklist into its {@code :local} slot, signing it once if they changed. */
	@Override
	protected void syncChildren() {
		if (contacts!=null) contacts.sync();
	}

	/** The regions this node's record names as its interests: the keys of the root lattice it was connected with, never {@code :local}. */
	ASet<Keyword> servedRegions() { return servedRegions; }

	/**
	 * Merges an incoming root projected to what this node wants ({@link #project}).
	 * The registry takes the {@code :p2p} region and the lattice merge the rest,
	 * verifying each owner's slot. A path delivery arrives here reduced to its path,
	 * so the same projection selects it.
	 */
	@Override
	@SuppressWarnings("unchecked")
	public Index<Keyword,ACell> merge(Index<Keyword,ACell> other) {
		Index<Keyword,ACell> wanted=project(other);
		if (wanted==null) return cursor().get();
		if (wanted.get(P2PLattice.KEY_P2P) instanceof Index<?,?> region) registry.merge((Index<Keyword,ACell>)region);
		Index<Keyword,ACell> rest=wanted.dissoc(P2PLattice.KEY_P2P);
		return cursor().updateAndGet(own -> lattice.merge(own,rest));
	}

	/** Reduces a path delivery to a root with this application's lattice, then merges that root. */
	@Override
	protected Index<Keyword,ACell> mergeAtPath(ACell value,ACell... path) {
		Index<Keyword,ACell> reduced=LatticeOps.reduce(lattice,value,path);
		return reduced==null?cursor().get():merge(reduced);
	}

	/** The root lattice this application was connected with, which selects the regions it merges. */
	public KeyedLattice getLattice() { return lattice; }

	/**
	 * Projects a root to what this node exchanges with others: every region as
	 * declared except {@code :local}, which holds state that is not shared externally, and the
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
		return directory;
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
	static AForkableCursor<ACell> identitySlot(ALatticeComponent<Index<Keyword,ACell>> application, AccountKey userKey,
			BiFunction<ACell,ACell,SignedData<ACell>> signer) {
		var root=new IndexComponent<>(application.cursor());
		var ids=new OwnedMap<>(P2PLattice.ID_LATTICE,root.path(P2PLattice.KEY_ID));
		return ids.authoringCursor(userKey,value -> signer==null?null:signer.apply(userKey,value));
	}

	/** Cursor at {@code [:p2p :nodes owner :value]}, navigated and signed the same way. */
	static AForkableCursor<ACell> nodeSlot(ALatticeComponent<Index<Keyword,ACell>> application, AccountKey userKey,
			BiFunction<ACell,ACell,SignedData<ACell>> signer) {
		var root=new IndexComponent<>(application.cursor());
		var p2p=new IndexComponent<Keyword,ACell>(root.path(P2PLattice.KEY_P2P));
		var nodes=new OwnedMap<>(P2PLattice.NODES_LATTICE,p2p.path(P2PLattice.KEY_NODES));
		return nodes.authoringCursor(userKey,value -> signer==null?null:signer.apply(userKey,value));
	}
}
