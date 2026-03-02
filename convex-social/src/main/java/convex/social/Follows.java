package convex.social;

import java.util.Set;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.ALatticeCursor;

/**
 * Cursor wrapper for a user's follow list.
 *
 * <p>A {@code Follows} wraps a lattice cursor at the follows level
 * ({@code AHashMap<ACell, ACell>} with LWW per entry). The {@code :active}
 * flag enables follow/unfollow toggling — the latest timestamped record
 * for each followed key wins.</p>
 *
 * <pre>{@code
 * Follows follows = social.user(myKey).follows();
 * follows.follow(bobKey);
 * follows.unfollow(bobKey);
 * Set<AccountKey> active = follows.getActive();
 * }</pre>
 */
public class Follows extends ALatticeComponent<AHashMap<ACell, ACell>> {

	Follows(ALatticeCursor<AHashMap<ACell, ACell>> cursor) {
		super(cursor);
	}

	/**
	 * Follows a user.
	 *
	 * @param target Account key of the user to follow
	 */
	public void follow(AccountKey target) {
		long ts = System.currentTimeMillis();
		AHashMap<?, ?> record = SocialPost.createFollowRecord(ts, true);
		cursor.updateAndGet(follows -> follows.assoc(target, record));
	}

	/**
	 * Unfollows a user.
	 *
	 * @param target Account key of the user to unfollow
	 */
	public void unfollow(AccountKey target) {
		long ts = System.currentTimeMillis();
		AHashMap<?, ?> record = SocialPost.createFollowRecord(ts, false);
		cursor.updateAndGet(follows -> follows.assoc(target, record));
	}

	/**
	 * Checks if a user is actively followed.
	 *
	 * @param target Account key to check
	 * @return true if the user is actively followed
	 */
	@SuppressWarnings("unchecked")
	public boolean isFollowing(AccountKey target) {
		AHashMap<ACell, ACell> follows = cursor.get();
		if (follows == null) return false;
		ACell record = follows.get(target);
		if (record instanceof AHashMap<?,?> map) {
			return SocialPost.isActiveFollow((AHashMap<Keyword, ACell>) map);
		}
		return false;
	}

	/**
	 * Gets the set of actively followed account keys.
	 *
	 * @return Set of actively followed keys
	 */
	public Set<AccountKey> getActive() {
		return SocialHelpers.getActiveFollows(cursor.get());
	}

}
