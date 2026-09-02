package convex.social;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;
import convex.lattice.ALattice;
import convex.lattice.generic.IndexLattice;
import convex.lattice.generic.JSONLattice;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.LWPLattice;
import convex.lattice.generic.LWWLattice;
import convex.lattice.generic.MapLattice;
import convex.lattice.generic.MaxLattice;
import convex.lattice.generic.StampingLattice;

/**
 * Lattice for a single user's social network state.
 *
 * <p>The user state is an {@code Index<Keyword, ACell>} with three keys:</p>
 * <ul>
 *   <li>{@link #KEY_FEED} — post feed ({@code Index<Blob, ACell>}, 8-byte timestamp keys, LWW per entry)</li>
 *   <li>{@link #KEY_PROFILE} — user profile (LWW register)</li>
 *   <li>{@link #KEY_FOLLOWING} — stamped LWP section containing the DID-keyed
 *       {@link #KEY_FOLLOWS} map with whole-record LWW values</li>
 * </ul>
 *
 * <p>Merge strategy:</p>
 * <ul>
 *   <li>Feed: IndexLattice with LWW per entry — union of posts, edits resolve by timestamp</li>
 *   <li>Profile: LWW — latest profile wins</li>
 *   <li>Following: LWP wrapper whose follow map merges distinct targets and resolves
 *       edits to one target by that complete record's timestamp</li>
 * </ul>
 */
public class SocialLattice extends ALattice<Index<Keyword, ACell>> {

	public static final SocialLattice INSTANCE = new SocialLattice();

	public static final Keyword KEY_FEED = Keyword.intern("feed");
	public static final Keyword KEY_PROFILE = Keyword.intern("profile");
	public static final Keyword KEY_FOLLOWING = Keyword.intern("following");
	public static final Keyword KEY_FOLLOWS = Keyword.intern("follows");

	static final IndexLattice<Blob, ACell> FEED_LATTICE =
		IndexLattice.create(LWWLattice.INSTANCE);

	private static final ALattice<ACell> FOLLOW_RECORD_LATTICE =
		StampingLattice.create(
			LWWLattice.create(JSONLattice.INSTANCE,SocialLattice::recordTimestamp),
			SocialLattice::stampRecord);

	static final MapLattice<ACell, ACell> FOLLOWS_LATTICE =
		new DIDFollowMapLattice(FOLLOW_RECORD_LATTICE);

	private static final KeyedLattice FOLLOWING_STRUCTURE=KeyedLattice.create(
		LWWLattice.KEY_TIMESTAMP,MaxLattice.INSTANCE,
		KEY_FOLLOWS,FOLLOWS_LATTICE);

	static final ALattice<Index<Keyword,ACell>> FOLLOWING_LATTICE=
		StampingLattice.create(
			LWPLattice.create(FOLLOWING_STRUCTURE,SocialLattice::followingTimestamp),
			SocialLattice::stampFollowing);

	private SocialLattice() {
		// Singleton
	}

	@Override
	public Index<Keyword, ACell> merge(Index<Keyword, ACell> ownValue, Index<Keyword, ACell> otherValue) {
		if (otherValue == null) return ownValue;
		if (ownValue == null) ownValue=zero();
		if (Utils.equals(ownValue, otherValue)) return ownValue;

		// Merge feed via IndexLattice
		Index<Blob, ACell> ownFeed = getFeed(ownValue);
		Index<Blob, ACell> otherFeed = getFeed(otherValue);
		Index<Blob, ACell> mergedFeed = FEED_LATTICE.merge(ownFeed, otherFeed);

		// Merge profile via LWW
		ACell ownProfile = ownValue.get(KEY_PROFILE);
		ACell otherProfile = otherValue.get(KEY_PROFILE);
		ACell mergedProfile = LWWLattice.INSTANCE.merge(ownProfile, otherProfile);

		// Merge the independently stamped following section. LWP only establishes
		// operand preference; distinct target records still merge structurally.
		Index<Keyword,ACell> ownFollowing=getFollowing(ownValue);
		Index<Keyword,ACell> otherFollowing=getFollowing(otherValue);
		Index<Keyword,ACell> mergedFollowing=FOLLOWING_LATTICE.merge(ownFollowing,otherFollowing);

		// Build result, only updating changed fields
		Index<Keyword, ACell> result = ownValue;
		if (!Utils.equals(mergedFeed, ownFeed)) {
			result = result.assoc(KEY_FEED, mergedFeed);
		}
		if (!Utils.equals(mergedProfile, ownProfile)) {
			result = result.assoc(KEY_PROFILE, mergedProfile);
		}
		if (!Utils.equals(mergedFollowing, ownFollowing)) {
			result = result.assoc(KEY_FOLLOWING, mergedFollowing);
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
			if (k.equals(KEY_FOLLOWING)) return (ALattice<T>) FOLLOWING_LATTICE;
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
	 * Gets the DID-keyed follows map from the {@code :following} section.
	 */
	@SuppressWarnings("unchecked")
	public static AHashMap<ACell, ACell> getFollows(Index<Keyword, ACell> state) {
		Index<Keyword,ACell> following=getFollowing(state);
		ACell follows = following.get(KEY_FOLLOWS);
		if (follows == null) return Maps.empty();
		return (AHashMap<ACell, ACell>) follows;
	}

	/** Returns true iff a value is already in the canonical social-state shape. */
	@SuppressWarnings("unchecked")
	public static boolean isCanonicalState(ACell value) {
		if (!(value instanceof Index<?,?> raw)) return false;
		try {
			Index<Keyword,ACell> state=(Index<Keyword,ACell>)raw;
			return state.equals(INSTANCE.merge(zeroState(),state));
		} catch (RuntimeException e) {
			return false;
		}
	}

	@SuppressWarnings("unchecked")
	private static Index<Keyword,ACell> zeroState() {
		return (Index<Keyword,ACell>)Index.EMPTY;
	}

	@SuppressWarnings("unchecked")
	public static Index<Keyword,ACell> getFollowing(Index<Keyword,ACell> state) {
		if (state==null) return zeroState();
		ACell following=state.get(KEY_FOLLOWING);
		if (!(following instanceof Index<?,?>)) return zeroState();
		return (Index<Keyword,ACell>)following;
	}

	private static long followingTimestamp(Index<Keyword,ACell> following) {
		if (following==null) return 0;
		ACell timestamp=following.get(LWWLattice.KEY_TIMESTAMP);
		return (timestamp instanceof CVMLong l)?l.longValue():0;
	}

	private static Index<Keyword,ACell> stampFollowing(
			Index<Keyword,ACell> following,CVMLong timestamp) {
		if (following==null) following=getFollowing(null);
		return following.assoc(LWWLattice.KEY_TIMESTAMP,timestamp);
	}

	@SuppressWarnings("unchecked")
	private static long recordTimestamp(ACell value) {
		if (!(value instanceof AHashMap<?,?> map)) return 0;
		ACell timestamp=((AHashMap<Keyword,ACell>)map).get(LWWLattice.KEY_TIMESTAMP);
		return (timestamp instanceof CVMLong l)?l.longValue():0;
	}

	@SuppressWarnings("unchecked")
	private static ACell stampRecord(ACell value,CVMLong timestamp) {
		AHashMap<Keyword,ACell> record=(value instanceof AHashMap<?,?> map)
			?(AHashMap<Keyword,ACell>)map:Maps.empty();
		return record.assoc(LWWLattice.KEY_TIMESTAMP,timestamp);
	}

	/** Map boundary which drops malformed target DIDs and follow records. */
	private static final class DIDFollowMapLattice extends MapLattice<ACell,ACell> {
		DIDFollowMapLattice(ALattice<ACell> valueNode) {
			super(valueNode);
		}

		@Override
		public AHashMap<ACell,ACell> merge(AHashMap<ACell,ACell> own,
				AHashMap<ACell,ACell> other) {
			return super.merge(own,sanitise(other));
		}

		@Override
		public AHashMap<ACell,ACell> merge(convex.lattice.LatticeContext context,
				AHashMap<ACell,ACell> own,AHashMap<ACell,ACell> other) {
			return super.merge(context,own,sanitise(other));
		}

		private AHashMap<ACell,ACell> sanitise(AHashMap<ACell,ACell> other) {
			if (other==null) return null;
			AHashMap<ACell,ACell> clean=Maps.empty();
			for (var entry:other.entrySet()) {
				if (!(entry.getKey() instanceof convex.core.data.AString did)
						|| !convex.auth.did.DID.isCanonicalBase(did)) continue;
				ACell value=entry.getValue();
				if (!(value instanceof AHashMap<?,?> record)) continue;
				AHashMap<Keyword,ACell> typed=(AHashMap<Keyword,ACell>)record;
				if (!(typed.get(SocialPost.ACTIVE) instanceof CVMBool)
						|| !(typed.get(LWWLattice.KEY_TIMESTAMP) instanceof CVMLong)) continue;
				ACell cached=typed.get(Follows.KEY_ACCOUNT_KEY);
				if (cached!=null && convex.core.data.AccountKey.parse(cached)==null) continue;
				clean=clean.assoc(entry.getKey(),value);
			}
			return clean;
		}
	}
}
