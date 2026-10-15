package convex.social;

import convex.auth.did.DID;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.lattice.ALatticeComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.StampedCursor;

/**
 * Cursor wrapper for a single user's social state.
 *
 * <p>A {@code SocialUser} wraps a cursor at the {@link SocialLattice} level
 * (through the signing boundary). Writes are automatically signed by the
 * {@code SignedCursor} in the cursor chain. This component owns the per-user
 * level, so it declares the shape of each section it hands out from
 * {@link SocialLattice}.</p>
 *
 * <p>Provides domain-specific accessors:</p>
 * <ul>
 *   <li>{@link #feed()} — the user's post feed</li>
 *   <li>{@link #follows()} — the user's follow list</li>
 * </ul>
 *
 * @see Social#user(AccountKey)
 */
public class SocialUser extends ALatticeComponent<Index<Keyword, ACell>> implements TransactionalComponent<Index<Keyword, ACell>,SocialUser> {

	private final AString ownerDid;

	SocialUser(AForkableCursor<Index<Keyword, ACell>> cursor, AString ownerDid) {
		super(cursor);
		this.ownerDid = ownerDid;
	}

	/**
	 * Gets this user's post feed.
	 *
	 * @return Feed cursor wrapper
	 */
	public Feed feed() {
		return configured(new Feed(cursor.child(SocialLattice.KEY_FEED,SocialLattice.FEED_LATTICE),ownerDid));
	}

	/**
	 * Gets this user's follow list. The {@code :following} section is a stamped
	 * layer, so this component wraps it before descending: every follow change
	 * re-stamps the section with the write clock, which is what lets its
	 * last-write-wins merge pick the freshest.
	 *
	 * @return Follows cursor wrapper
	 */
	public Follows follows() {
		AForkableCursor<Index<Keyword, ACell>> following=StampedCursor.create(
			cursor.child(SocialLattice.KEY_FOLLOWING,SocialLattice.FOLLOWING_LATTICE),
			SocialLattice.FOLLOWING_LATTICE,getContext()::currentTimestamp);
		return configured(new Follows(following.child(SocialLattice.KEY_FOLLOWS,SocialLattice.FOLLOWS_LATTICE)));
	}

	/**
	 * Creates an isolated working copy of this user's unsigned social value.
	 * Multiple feed, profile and follow actions can be applied to the fork and
	 * {@link #sync() synced} through the owner boundary as one signed value,
	 * merged with {@link SocialLattice}.
	 *
	 * @return isolated working copy for this user
	 */
	public SocialUser fork() {
		return configured(new SocialUser(forkCursor(SocialLattice.INSTANCE),ownerDid));
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

	@Override
	public SocialUser withCursor(AForkableCursor<Index<Keyword, ACell>> cursor) {
		return configured(new SocialUser(cursor,ownerDid));
	}

}
