package convex.p2p;


import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.lattice.ALatticeComponent;

/**
 * Convenience facade over a single P2P user's independently located components.
 *
 * <p>Obtained from {@link P2PApplication#p2p(AccountKey)} or
 * {@link P2PNode#p2p(AccountKey)}, or made with {@link #create} from the
 * {@link P2PIdentities} and {@link P2PRegion} an application views. {@link #identity()} is the component at the user's
 * identity slot, {@code [:id <userKey> :value]}, and {@link #node()} the one at the
 * user's node record, {@code [:p2p :nodes <userKey> :value]}; both are already through
 * the signing boundary. This facade deliberately is not an {@link ALatticeComponent}:
 * it aggregates two disjoint lattice locations.
 *
 * <pre>{@code
 * P2PUser me = node.p2p();
 * me.setIdentity(P2PLattice.createIdentity(Strings.create("alice"), null, null, ts));
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
	 * Combines one user's identity and node record into a facade.
	 *
	 * @param identity The user's identity component
	 * @param node The same user's node-record component
	 * @return P2P user facade
	 */
	public static P2PUser create(P2PIdentity identity,P2PNodeRecord node) {
		if (identity==null || node==null) throw new IllegalArgumentException("Identity and node record must not be null");
		if (!identity.owner().equals(node.owner())) {
			throw new IllegalArgumentException("Identity and node record must belong to the same user");
		}
		return new P2PUser(identity,node,identity.owner());
	}

	/** Returns the path-specific identity component. */
	public P2PIdentity identity() {
		return identity;
	}

	/** Returns the path-specific node-record component. */
	public P2PNodeRecord node() {
		return node;
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
	 * forked node record would have no matching sync path. {@link #node()} on a fork
	 * is still the live node record.
	 *
	 * @return Forked P2PUser
	 */
	public P2PUser fork() {
		return new P2PUser(identity.fork(),node,userKey);
	}
}
