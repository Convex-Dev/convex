package convex.p2p;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.SignedCursor;

/**
 * Convenience facade over a single P2P user's independently located components.
 *
 * <p>Obtained from {@link P2PNode#p2p(AccountKey)}. {@link #cursor()} returns a cursor
 * rooted at that user's identity slot — {@code [:id <userKey> :value]} — already through
 * the signing boundary. {@link #identity()} and {@link #node()} expose the two
 * path-specific components. This facade deliberately is not an
 * {@link ALatticeComponent}: it aggregates two disjoint lattice locations.
 *
 * <pre>{@code
 * P2PUser me = node.p2p();
 * me.cursor().set(P2PLattice.createIdentity(Strings.create("alice"), null, null, ts));
 * me.sync();   // push back to the lattice root
 * }</pre>
 *
 * <h2>Signing is the lattice machinery's job</h2>
 *
 * <p>Navigating {@code :value} crosses {@code SignedLattice}'s write boundary, so the
 * cursor chain contains a {@code SignedCursor}. That cursor projects the unsigned value
 * on read and signs on write through its {@code LatticeContext}, which is asked for a
 * signer authorised for the owner the path selected. Applications read and write plain
 * values and never touch {@code SignedData}. {@link #sync()} pushes the result up to
 * the root (and, on a launched node, into persistence and propagation).
 *
 * <h2>Writing an owned area</h2>
 *
 * <p>{@code p2p(someoneElse)} is always a valid read view. A write succeeds only when
 * the context signing policy can supply a signer authorised for that user — the same
 * rule {@code OwnerLattice} applies to data arriving on merge, so a write that would
 * be rejected by every peer fails here instead of entering local state. A wallet or
 * key-store-backed context can therefore manage several identities without treating
 * one as primary.
 */
public class P2PUser {

	private final P2PIdentity identity;
	private final P2PNodeRecord node;
	private final AccountKey userKey;

	private P2PUser(P2PIdentity identity, P2PNodeRecord node, AccountKey userKey) {
		this.identity=identity;
		this.node=node;
		this.userKey = userKey;
	}

	/**
	 * Creates a user view over a P2P root cursor.
	 *
	 * <p>Both cursors inherit the root cursor's {@code LatticeContext} live, so writes
	 * request {@code userKey} from that policy when signed.
	 *
	 * @param rootCursor Cursor at the {@link P2PLattice#ROOT} level
	 * @param userKey The P2P user's account key
	 * @return A view of that user's owned area
	 */
	public static P2PUser create(ALatticeCursor<?> rootCursor, AccountKey userKey) {
		if (rootCursor == null) throw new IllegalArgumentException("Root cursor must not be null");
		if (userKey == null) throw new IllegalArgumentException("User key must not be null");

		return new P2PUser(
			new P2PIdentity(SignedCursor.forOwner(
				rootCursor.path(P2PLattice.KEY_ID,userKey),P2PLattice.ID_LATTICE,userKey),userKey),
			new P2PNodeRecord(SignedCursor.forOwner(
				rootCursor.path(P2PLattice.KEY_P2P,P2PLattice.KEY_NODES,userKey),
				P2PLattice.NODES_LATTICE,userKey),userKey),
			userKey);
	}

	/**
	 * Creates a user facade over a containing application component's cursor.
	 *
	 * @param parent Containing application component at the P2P root level
	 * @param userKey P2P user key
	 * @return P2P user facade
	 */
	public static P2PUser create(ALatticeComponent<?> parent, AccountKey userKey) {
		if (parent==null) throw new IllegalArgumentException("Parent component must not be null");
		return create(parent.cursor(),userKey);
	}

	/** Returns the path-specific identity component. */
	public P2PIdentity identity() {
		return identity;
	}

	/** Returns the path-specific node-record component. */
	public P2PNodeRecord node() {
		return node;
	}

	/** Compatibility shortcut for {@code identity().cursor()}. */
	public ALatticeCursor<ACell> cursor() {
		return identity.cursor();
	}

	/**
	 * Gets this user's account key — the owner key their slots are keyed by, and the
	 * key that must sign them.
	 *
	 * @return The user's Ed25519 public key
	 */
	public AccountKey getUserKey() {
		return userKey;
	}

	/**
	 * Gets a cursor at this user's node record, {@code [:p2p :nodes <userKey> :value]},
	 * also through the signing boundary and scoped the same way.
	 *
	 * <p>Separate from {@link #cursor()} because the node registry is a distinct
	 * top-level region: identity claims and transport details are published, and
	 * replicated, independently.
	 *
	 * @return Cursor at this user's NodeInfo record
	 */
	public ALatticeCursor<ACell> nodeCursor() {
		return node.cursor();
	}

	/**
	 * Reads this user's IdentityInfo map.
	 *
	 * @return The IdentityInfo map, or null if this user has published none
	 */
	public AHashMap<Keyword, ACell> getIdentity() {
		return identity.getIdentity();
	}

	/**
	 * Publishes an IdentityInfo map for this user. Signed on write; call {@link #sync()}
	 * to push it up to the lattice root.
	 *
	 * @param identity IdentityInfo map, typically from {@link P2PLattice#createIdentity}
	 * @throws IllegalStateException if the user's signer is unavailable in context
	 */
	public void setIdentity(AHashMap<Keyword, ACell> identity) {
		this.identity.setIdentity(identity);
	}

	/**
	 * Convenience for the common case: publish name and operated node keys with an
	 * explicit timestamp.
	 *
	 * @param name Display name (may be null)
	 * @param nodes Node keys this user operates (may be null)
	 * @param timestamp Timestamp in millis, used for LWW ordering
	 * @throws IllegalStateException if the user's signer is unavailable in context
	 */
	public void setIdentity(AString name, AVector<ACell> nodes, long timestamp) {
		identity.setIdentity(name,nodes,timestamp);
	}

	/** Synchronises identity changes to this facade's live parent path. */
	public void sync() {
		identity.sync();
	}

	/**
	 * Creates a forked view of the identity area for batch edits. Changes are isolated
	 * until {@link #sync()}.
	 *
	 * <p>Only the identity cursor is forked — {@link #sync()} syncs that cursor, so a
	 * forked node record would have no matching sync path. {@link #nodeCursor()} on a
	 * fork still addresses the live node record.
	 *
	 * @return Forked P2PUser
	 */
	public P2PUser fork() {
		return new P2PUser(identity.fork(),node,userKey);
	}
}
