package convex.p2p;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.LWWLattice;
import convex.lattice.generic.OwnerLattice;
import convex.lattice.generic.ReservedLattice;
import convex.social.Social;

/**
 * The root lattice for a Convex P2P node — the top-level regions the P2P system needs
 * to know about and merge.
 *
 * <p>This root declares <em>only</em> P2P concerns — who is on the network, what they
 * run, and how to reach them. It deliberately excludes the application regions of
 * {@code convex.lattice.Lattice#ROOT} ({@code :data}, {@code :fs}, {@code :kv},
 * {@code :queue}).
 *
 * <h2>Part of a rollup</h2>
 *
 * <p>convex-p2p is a rollup package: one dependency giving a complete node. This class
 * holds both halves of its region story — {@link #ROOT}, the infrastructure regions the
 * module defines itself, and {@link #NODE_ROOT}, those plus the application regions
 * bundled with a node. There is one node server ({@link P2PNode}) serving whichever set
 * is chosen, not a separate node per application.
 *
 * <p>Region sets need not match across a network: an unrecognised top-level region is
 * ignored on merge rather than rejected, so a node serving only {@code ROOT} and one
 * serving {@code NODE_ROOT} interoperate on everything they have in common. That is what
 * makes switching a region off a safe local decision.
 *
 * <p>A region outside the rollup is composed the same way {@link #NODE_ROOT} composes
 * social; one that should ship with every node is added to {@code NODE_ROOT} instead.
 *
 * <pre>{@code
 * KeyedLattice root = P2PLattice.NODE_ROOT.addLattice(MyApp.KEY, MyApp.LATTICE);
 * }</pre>
 *
 * <h2>Structure</h2>
 * <pre>
 *   P2PLattice.ROOT (KeyedLattice)                 infrastructure floor
 *   ├── :p2p → KeyedLattice                        shared node registry
 *   │     └── :nodes → OwnerLattice(LWWLattice)      node key → Signed(NodeInfo)
 *   ├── :id  → OwnerLattice(LWWLattice)            user key → Signed(IdentityInfo)
 *   └── :kad → ReservedLattice                     reserved, nothing merges yet
 *
 *   P2PLattice.NODE_ROOT                           what a node serves by default
 *   └── ROOT + :social → Social.SOCIAL_LATTICE
 * </pre>
 *
 * <h2>Independent top-level regions</h2>
 *
 * <p>The three regions are siblings, not nested, because a lattice node ignores any
 * top-level region it does not recognise: {@code AKeyedLattice} merge iterates its
 * <em>registered</em> keys, so an incoming key with no registered lattice is never
 * visited and is simply dropped. A node running {@code Lattice.ROOT} therefore merges
 * {@code :p2p} normally and discards {@code :id} and {@code :kad} without error, and a
 * P2P node discards {@code :data}/{@code :fs}/{@code :kv}/{@code :queue} the same way.
 * Regions can be added, adopted and retired independently.
 *
 * <p>{@code :p2p} keeps the shape core gave it — a {@link KeyedLattice} containing
 * {@code :nodes} — so the registry is addressed as {@code [:p2p :nodes]} on every root.
 * That matters because lattice paths are wire-visible: a {@code LATTICE_VALUE} message
 * carries {@code [:LV id [*path*] value]} and the receiver merges at that literal path.
 * {@link NodeDirectory} owns publication and discovery at exactly this path; the generic
 * {@code NodeServer} transports it without interpreting the path.
 *
 * <h2>Relationship to {@code convex.lattice.P2PLattice}</h2>
 *
 * <p>Core declares the {@code :p2p} region and wires it into {@code Lattice.ROOT}. This
 * class reuses that same {@link convex.lattice.P2PLattice#LATTICE} instance rather than
 * redeclaring it, so the registry's merge semantics cannot drift between the two roots,
 * and adds the P2P-only {@code :id} and {@code :kad} regions alongside it.
 *
 * <h2>Owner binding — always merge with a context</h2>
 *
 * <p>Both populated regions are {@link OwnerLattice}s keyed by {@link AccountKey}, so a
 * user can only ever write their own slot. That guarantee is enforced by
 * {@code LatticeContext.verifyOwner}, which {@code OwnerLattice} calls <em>only</em> from
 * its context-aware merge. The two-argument {@code merge(own, other)} does not check the
 * signer against the owner key at all, and would accept an impersonated slot.
 *
 * <p>This is safe on every real path — {@code ALatticeCursor.merge} always calls
 * {@code lattice.merge(getContext(), ..)}, and even {@code LatticeContext.EMPTY} triggers
 * the {@code AccountKey} equality fast path — so inbound network values merged by
 * {@code NodeServer} are owner-checked by default. But code that reaches for a raw
 * {@code ALattice.merge(a, b)} on these regions loses the guarantee silently. Merge
 * through a cursor, or pass a context explicitly.
 *
 * <p>Helpers for building and reading {@code NodeInfo} records live in
 * {@link convex.lattice.P2PLattice} ({@code createNodeInfo}, {@code createSignedEntry},
 * {@code getNodeInfo}). Identity helpers are below.
 */
public class P2PLattice {

	// ========== Region keys ==========

	/** Top-level {@code :p2p} region: the shared node registry. */
	public static final Keyword KEY_P2P = Keywords.P2P;

	/** The {@code :nodes} sub-region of {@code :p2p}: node records signed by their P2P user. */
	public static final Keyword KEY_NODES = Keywords.NODES;

	/** Top-level {@code :id} region: P2P user identity records. */
	public static final Keyword KEY_ID = Keywords.ID;

	/** Top-level {@code :kad} region: reserved for Kademlia routing. */
	public static final Keyword KEY_KAD = Keyword.intern("kad");

	// ========== IdentityInfo field keys ==========

	/** Identity field: {@code CVMLong} millis, drives LWW ordering. */
	public static final Keyword ID_TIMESTAMP = Keywords.TIMESTAMP;

	/** Identity field: display name for this P2P user. */
	public static final Keyword ID_NAME = Keywords.NAME;

	/** Identity field: {@code AVector} of node {@link AccountKey}s this user operates. */
	public static final Keyword ID_NODES = Keywords.NODES;

	/** Identity field: optional additional metadata map. */
	public static final Keyword ID_METADATA = Keywords.METADATA;

	// ========== Region lattices ==========

	/**
	 * {@code :p2p} — the shared node registry, reusing core's region instance so it
	 * merges identically on this root and on {@code Lattice.ROOT}. Contains
	 * {@code :nodes}, where each P2P user owns a signed, LWW node record keyed by
	 * their {@link AccountKey}.
	 */
	public static final KeyedLattice P2P_LATTICE = convex.lattice.P2PLattice.LATTICE;

	/**
	 * {@code :nodes} — the node registry lattice inside {@link #P2P_LATTICE}, exposed
	 * for direct use.
	 */
	public static final OwnerLattice<ACell> NODES_LATTICE =
		convex.lattice.P2PLattice.NODES_LATTICE;

	/**
	 * {@code :id} — each P2P user owns a signed, LWW identity record keyed by their
	 * {@link AccountKey}. Separate from the node registry so one identity can advertise
	 * several nodes, and so identity claims change independently of transport details.
	 */
	public static final OwnerLattice<ACell> ID_LATTICE =
		OwnerLattice.create(LWWLattice.INSTANCE);

	/**
	 * {@code :kad} — reserved for Kademlia routing state. Declared so the path is
	 * claimed and stable, but nothing merges here: routing semantics are still open
	 * (see {@code P2P_DESIGN.md} §4.3 and decision 13.2, which lean towards k-buckets
	 * being node-local rather than propagated).
	 */
	public static final ReservedLattice KAD_LATTICE =
		ReservedLattice.create("Kademlia routing: merge semantics not yet designed");

	// ========== Composition ==========

	/**
	 * The P2P regions alone: node registry, user identity and reserved routing, as
	 * independent top-level siblings.
	 *
	 * <p>This is the infrastructure floor — what a node must serve to be a useful
	 * participant. It is also the root to run when every application region is switched
	 * off; such a node is still a fully capable discovery node.
	 *
	 * <p>For the regions a node serves by default, see {@link #NODE_ROOT}.
	 */
	public static final KeyedLattice ROOT = KeyedLattice.create(
		KEY_P2P, P2P_LATTICE,
		KEY_ID, ID_LATTICE,
		KEY_KAD, KAD_LATTICE
	);

	/**
	 * The regions a standard node serves: {@link #ROOT} plus the application regions
	 * rolled up into this package.
	 *
	 * <p>There is one node server ({@link P2PNode}), not a P2P one and a social one.
	 * Which regions it serves is a per-node choice, not a per-build one — an operator
	 * who does not want social passes {@link #ROOT} instead, and still runs a complete
	 * discovery node. Regions a node does not serve are ignored on merge rather than
	 * rejected, so nodes with different region sets interoperate.
	 *
	 * <p>This is the single place the bundle is declared: a region that should ship with
	 * every node is added here, alongside its dependency in this module's POM.
	 */
	public static final KeyedLattice NODE_ROOT =
		ROOT.addLattice(Social.KEY_SOCIAL, Social.SOCIAL_LATTICE);

	// ========== Identity helpers ==========

	/**
	 * Creates an IdentityInfo map for a P2P user.
	 *
	 * <p>As with NodeInfo, the timestamp is supplied by the caller — the driving process
	 * or a {@code LatticeContext} — rather than read from the system clock here, so that
	 * merges are reproducible and testable.
	 *
	 * @param name Display name for this identity (may be null)
	 * @param nodes Node {@link AccountKey}s operated by this identity (may be null)
	 * @param metadata Additional metadata (may be null)
	 * @param timestamp Timestamp in millis, used for LWW ordering
	 * @return IdentityInfo map
	 */
	public static AHashMap<Keyword, ACell> createIdentity(
			AString name, AVector<ACell> nodes,
			AHashMap<Keyword, ACell> metadata, long timestamp) {
		AHashMap<Keyword, ACell> identity = Maps.of(
			ID_TIMESTAMP, CVMLong.create(timestamp),
			ID_NODES, (nodes != null) ? nodes : Vectors.empty()
		);
		if (name != null) identity = identity.assoc(ID_NAME, name);
		if (metadata != null) identity = identity.assoc(ID_METADATA, metadata);
		return identity;
	}

	/**
	 * Signs an IdentityInfo map into an OwnerLattice entry for merge at {@code :id}.
	 *
	 * @param keyPair The P2P user's key pair
	 * @param identity IdentityInfo map (from {@link #createIdentity})
	 * @return Single-entry owner map: {AccountKey → Signed(IdentityInfo)}
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static AHashMap<ACell, SignedData<ACell>> createSignedIdentity(
			AKeyPair keyPair, AHashMap<Keyword, ACell> identity) {
		SignedData<ACell> signed = keyPair.signData((ACell) identity);
		return (AHashMap) Maps.of(keyPair.getAccountKey(), signed);
	}

	/**
	 * Gets the IdentityInfo map for a P2P user from a merged {@code :id} value.
	 *
	 * @param identityValue The OwnerLattice value (map of AccountKey → SignedData)
	 * @param userKey The P2P user's public key
	 * @return IdentityInfo map, or null if not present
	 */
	@SuppressWarnings("unchecked")
	public static AHashMap<Keyword, ACell> getIdentity(
			AHashMap<ACell, SignedData<ACell>> identityValue, AccountKey userKey) {
		if (identityValue == null) return null;
		SignedData<ACell> signed = identityValue.get(userKey);
		if (signed == null) return null;
		return (AHashMap<Keyword, ACell>) signed.getValue();
	}

	/**
	 * Gets the node keys advertised by a P2P user's identity record.
	 *
	 * @param identityValue The OwnerLattice value at {@code :id}
	 * @param userKey The P2P user's public key
	 * @return Vector of node keys, or null if the identity is not present
	 */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> getIdentityNodes(
			AHashMap<ACell, SignedData<ACell>> identityValue, AccountKey userKey) {
		AHashMap<Keyword, ACell> identity = getIdentity(identityValue, userKey);
		if (identity == null) return null;
		return (AVector<ACell>) identity.get(ID_NODES);
	}
}
