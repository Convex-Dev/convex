package convex.social;

import convex.auth.did.DID;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.ALatticeCursor;

/**
 * Cursor wrapper for a single user's social state.
 *
 * <p>A {@code SocialUser} wraps a cursor at the {@link SocialLattice} level
 * (through the signing boundary). Writes are automatically signed by the
 * {@code SignedCursor} in the cursor chain.</p>
 *
 * <p>Provides domain-specific accessors:</p>
 * <ul>
 *   <li>{@link #feed()} — the user's post feed</li>
 *   <li>{@link #follows()} — the user's follow list</li>
 * </ul>
 *
 * @see Social#user(AccountKey)
 */
public class SocialUser extends ALatticeComponent<Index<Keyword, ACell>> {

	private final AString ownerDid;

	SocialUser(Social parent, ALatticeCursor<Index<Keyword, ACell>> cursor, AString ownerDid) {
		super(parent,cursor);
		this.ownerDid = ownerDid;
	}

	/**
	 * Gets this user's post feed.
	 *
	 * @return Feed cursor wrapper
	 */
	public Feed feed() {
		return new Feed(this,cursor.path(SocialLattice.KEY_FEED),ownerDid);
	}

	/**
	 * Gets this user's follow list.
	 *
	 * @return Follows cursor wrapper
	 */
	public Follows follows() {
		return new Follows(this,cursor.path(
			SocialLattice.KEY_FOLLOWING,SocialLattice.KEY_FOLLOWS));
	}

	/**
	 * Creates an isolated working copy of this user's unsigned social value.
	 * Multiple feed, profile and follow actions can be applied to the fork and
	 * {@link #sync() synced} through the owner boundary as one signed value.
	 *
	 * @return isolated working copy for this user
	 */
	public SocialUser fork() {
		return new SocialUser((Social) parent(),cursor.fork(),ownerDid);
	}

	// TODO: public Profile profile() { ... }

	/**
	 * Gets this user's account key.
	 *
	 * @return The owner's Ed25519 public key
	 */
	public AccountKey getOwnerKey() {
		return DID.keyFromDID(ownerDid);
	}

	/** Gets the stable social owner DID. */
	public AString getOwnerDID() {
		return ownerDid;
	}

}
