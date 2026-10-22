package convex.p2p;

import java.util.function.BiFunction;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.SignedData;
import convex.lattice.ALatticeApplication;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.AForkableCursor;

/**
 * Convenience facade over a single P2P user's independently located components.
 *
 * <p>Obtained from {@link P2PApplication#p2p(AccountKey)} or
 * {@link P2PNode#p2p(AccountKey)}. {@link #cursor()} returns a cursor
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
 * <h2>Signing is the owning component's job</h2>
 *
 * <p>The P2P application owns the owner-keyed {@code :id} and {@code :nodes} levels, so
 * it wraps each user's slot in a {@code SignedCursor} bound to that owner. That cursor
 * projects the unsigned value on read and signs on write through its
 * explicit authoring function, bound by the component to an authorised signer.
 * Applications read and write plain values and never touch {@code SignedData}.
 * {@link #sync()} pushes the result up to the root (and, on a launched node, into
 * persistence and propagation).
 *
 * <h2>Writing an owned area</h2>
 *
 * <p>{@code p2p(someoneElse)} is always a valid read view. A write succeeds only when
 * the explicit signer can supply a signer authorised for that user — the same
 * rule {@code OwnerLattice} applies to data arriving on merge, so a write that would
 * be rejected by every peer fails here instead of entering local state. A wallet or
 * key-store-backed signer can therefore manage several identities without treating
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
	 * Creates a user facade over an application whose root lattice includes the P2P
	 * regions. This overload opens a read view without signing capability.
	 *
	 * @param application Application at the root whose lattice declares the P2P regions
	 * @param userKey P2P user key
	 * @return P2P user facade
	 */
	public static P2PUser create(ALatticeApplication<Index<Keyword,ACell>> application,AccountKey userKey) {
		return create(application,userKey,null);
	}

	/** Opens both owned slots with one owner-aware signer, which may serve multiple keys. */
	public static P2PUser create(ALatticeApplication<Index<Keyword,ACell>> application, AccountKey userKey,
			BiFunction<ACell,ACell,SignedData<ACell>> signer) {
		if (application==null) throw new IllegalArgumentException("Application must not be null");
		if (userKey==null) throw new IllegalArgumentException("User key must not be null");
		return new P2PUser(
			new P2PIdentity(P2PApplication.identitySlot(application,userKey,signer),userKey),
			new P2PNodeRecord(P2PApplication.nodeSlot(application,userKey,signer),userKey),
			userKey);
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
	public AForkableCursor<ACell> cursor() {
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
	public AForkableCursor<ACell> nodeCursor() {
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
	 * @throws IllegalStateException if the user's signer is unavailable
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
	 * @throws IllegalStateException if the user's signer is unavailable
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
