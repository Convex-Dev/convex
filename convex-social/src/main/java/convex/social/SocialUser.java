package convex.social;

import convex.core.data.ACell;
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

	private final AccountKey ownerKey;

	SocialUser(ALatticeCursor<Index<Keyword, ACell>> cursor, AccountKey ownerKey) {
		super(cursor);
		this.ownerKey = ownerKey;
	}

	/**
	 * Gets this user's post feed.
	 *
	 * @return Feed cursor wrapper
	 */
	public Feed feed() {
		return new Feed(cursor.path(SocialLattice.KEY_FEED), ownerKey);
	}

	/**
	 * Gets this user's follow list.
	 *
	 * @return Follows cursor wrapper
	 */
	public Follows follows() {
		return new Follows(cursor.path(SocialLattice.KEY_FOLLOWS));
	}

	// TODO: public Profile profile() { ... }

	/**
	 * Gets this user's account key.
	 *
	 * @return The owner's Ed25519 public key
	 */
	public AccountKey getOwnerKey() {
		return ownerKey;
	}

}
