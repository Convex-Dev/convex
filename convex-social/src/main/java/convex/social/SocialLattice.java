package convex.social;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.util.Utils;
import convex.lattice.ALattice;
import convex.lattice.generic.IndexLattice;
import convex.lattice.generic.LWWLattice;
import convex.lattice.generic.MapLattice;

/**
 * Lattice for a single user's social network state.
 *
 * <p>The user state is an {@code Index<Keyword, ACell>} with three keys:</p>
 * <ul>
 *   <li>{@link #KEY_FEED} — post feed ({@code Index<Blob, ACell>}, 8-byte timestamp keys, LWW per entry)</li>
 *   <li>{@link #KEY_PROFILE} — user profile (LWW register)</li>
 *   <li>{@link #KEY_FOLLOWS} — follow map ({@code AHashMap<ACell, ACell>}, key → {active, timestamp})</li>
 * </ul>
 *
 * <p>Merge strategy:</p>
 * <ul>
 *   <li>Feed: IndexLattice with LWW per entry — union of posts, edits resolve by timestamp</li>
 *   <li>Profile: LWW — latest profile wins</li>
 *   <li>Follows: MapLattice with LWW per entry — follow/unfollow resolves by timestamp</li>
 * </ul>
 */
public class SocialLattice extends ALattice<Index<Keyword, ACell>> {

	public static final SocialLattice INSTANCE = new SocialLattice();

	public static final Keyword KEY_FEED = Keyword.intern("feed");
	public static final Keyword KEY_PROFILE = Keyword.intern("profile");
	public static final Keyword KEY_FOLLOWS = Keyword.intern("follows");

	static final IndexLattice<Blob, ACell> FEED_LATTICE =
		IndexLattice.create(LWWLattice.INSTANCE);

	static final MapLattice<ACell, ACell> FOLLOWS_LATTICE =
		MapLattice.create(LWWLattice.INSTANCE);

	private SocialLattice() {
		// Singleton
	}

	@Override
	public Index<Keyword, ACell> merge(Index<Keyword, ACell> ownValue, Index<Keyword, ACell> otherValue) {
		if (otherValue == null) return ownValue;
		if (ownValue == null) {
			if (checkForeign(otherValue)) return otherValue;
			return zero();
		}
		if (Utils.equals(ownValue, otherValue)) return ownValue;

		// Merge feed via IndexLattice
		Index<Blob, ACell> ownFeed = getFeed(ownValue);
		Index<Blob, ACell> otherFeed = getFeed(otherValue);
		Index<Blob, ACell> mergedFeed = FEED_LATTICE.merge(ownFeed, otherFeed);

		// Merge profile via LWW
		ACell ownProfile = ownValue.get(KEY_PROFILE);
		ACell otherProfile = otherValue.get(KEY_PROFILE);
		ACell mergedProfile = LWWLattice.INSTANCE.merge(ownProfile, otherProfile);

		// Merge follows via MapLattice
		AHashMap<ACell, ACell> ownFollows = getFollows(ownValue);
		AHashMap<ACell, ACell> otherFollows = getFollows(otherValue);
		AHashMap<ACell, ACell> mergedFollows = FOLLOWS_LATTICE.merge(ownFollows, otherFollows);

		// Build result, only updating changed fields
		Index<Keyword, ACell> result = ownValue;
		if (!Utils.equals(mergedFeed, ownFeed)) {
			result = result.assoc(KEY_FEED, mergedFeed);
		}
		if (!Utils.equals(mergedProfile, ownProfile)) {
			result = result.assoc(KEY_PROFILE, mergedProfile);
		}
		if (!Utils.equals(mergedFollows, ownFollows)) {
			result = result.assoc(KEY_FOLLOWS, mergedFollows);
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	@Override
	public Index<Keyword, ACell> zero() {
		return (Index<Keyword, ACell>) Index.EMPTY;
	}

	@Override
	public boolean checkForeign(Index<Keyword, ACell> value) {
		return (value instanceof Index);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		if (childKey instanceof Keyword k) {
			if (k.equals(KEY_FEED)) return (ALattice<T>) FEED_LATTICE;
			if (k.equals(KEY_PROFILE)) return (ALattice<T>) LWWLattice.INSTANCE;
			if (k.equals(KEY_FOLLOWS)) return (ALattice<T>) FOLLOWS_LATTICE;
		}
		return null;
	}

	// ===== Static helpers =====

	/**
	 * Gets the feed index from user state.
	 */
	@SuppressWarnings("unchecked")
	public static Index<Blob, ACell> getFeed(Index<Keyword, ACell> state) {
		if (state == null) return Index.none();
		ACell feed = state.get(KEY_FEED);
		if (feed == null) return Index.none();
		return (Index<Blob, ACell>) feed;
	}

	/**
	 * Gets the profile from user state.
	 */
	@SuppressWarnings("unchecked")
	public static AHashMap<Keyword, ACell> getProfile(Index<Keyword, ACell> state) {
		if (state == null) return Maps.empty();
		ACell profile = state.get(KEY_PROFILE);
		if (profile == null) return Maps.empty();
		return (AHashMap<Keyword, ACell>) profile;
	}

	/**
	 * Gets the follows map from user state.
	 */
	@SuppressWarnings("unchecked")
	public static AHashMap<ACell, ACell> getFollows(Index<Keyword, ACell> state) {
		if (state == null) return Maps.empty();
		ACell follows = state.get(KEY_FOLLOWS);
		if (follows == null) return Maps.empty();
		return (AHashMap<ACell, ACell>) follows;
	}
}
