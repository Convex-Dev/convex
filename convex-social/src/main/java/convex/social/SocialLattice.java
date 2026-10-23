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
import convex.core.util.MergeFunction;
import convex.core.lang.RT;
import convex.core.data.prim.AInteger;
import convex.lattice.ALattice;
import convex.lattice.generic.IndexLattice;
import convex.lattice.generic.JSONLattice;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.LWPLattice;
import convex.lattice.generic.LWWLattice;
import convex.lattice.generic.MapLattice;
import convex.lattice.generic.MaxLattice;

/**
 * Lattice for a single user's social network state.
 *
 * <p>The user state is an {@code Index<Keyword, ACell>} with three keys:</p>
 * <ul>
 *   <li>{@link #KEY_FEED} — post feed ({@code Index<Blob, ACell>}, timestamp-prefixed keys, LWW per entry)</li>
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
		LWWLattice.create(JSONLattice.INSTANCE,SocialLattice::recordTimestamp);

	static final MapLattice<ACell, ACell> FOLLOWS_LATTICE =
		new DIDFollowMapLattice(FOLLOW_RECORD_LATTICE);

	private static final KeyedLattice FOLLOWING_STRUCTURE=KeyedLattice.create(
		LWWLattice.KEY_TIMESTAMP,MaxLattice.INSTANCE,
		KEY_FOLLOWS,FOLLOWS_LATTICE);

	static final ALattice<Index<Keyword,ACell>> FOLLOWING_LATTICE=
		LWPLattice.create(FOLLOWING_STRUCTURE,SocialLattice::followingTimestamp);

	private SocialLattice() {
		// Singleton
	}

	@Override
	public Index<Keyword, ACell> merge(Index<Keyword, ACell> ownValue, Index<Keyword, ACell> otherValue) {
		if (otherValue == null) return ownValue;
		if (ownValue == null) ownValue=zero();
		if (Utils.equals(ownValue, otherValue)) return ownValue;

		return ownValue.mergeDifferences(otherValue,new MergeFunction<ACell>() {
			@Override public ACell merge(ACell own,ACell other) {
				return own==null?other:own;
			}
			@Override public ACell merge(Object key,ACell own,ACell other) {
				if (other==null) return own;
				// Index keys compare by blob, including decoded/JSON key representations.
				ALattice<?> section=key instanceof ACell cell?INSTANCE.path(cell):null;
				if (section==FEED_LATTICE) return mergeFeed(own,other);
				if (section==FOLLOWING_LATTICE) return mergeFollowing(own,other);
				if (section==LWWLattice.INSTANCE) return LWWLattice.INSTANCE.merge(own,other);
				// Unknown fields are opaque. Carry new fields, retaining established conflicts.
				return merge(own,other);
			}
		});
	}

	private static ACell mergeFeed(ACell own,ACell other) {
		Index<Blob,ACell> a=RT.ensureIndex(own), b=RT.ensureIndex(other);
		if (b==null || (own!=null && a==null)) return own==null?other:own;
		return FEED_LATTICE.merge(a,b);
	}

	private static ACell mergeFollowing(ACell own,ACell other) {
		Index<Keyword,ACell> a=RT.ensureIndex(own), b=RT.ensureIndex(other);
		if (!canMergeFollowing(b) || (own!=null && !canMergeFollowing(a))) return own==null?other:own;
		var merged=FOLLOWING_LATTICE.merge(a,b);
		// The LWP structure knows only timestamp/follows. Preserve extensions even
		// when its timestamp preference reverses the operands internally.
		merged=retainFollowingExtensions(merged,a);
		return retainFollowingExtensions(merged,b);
	}

	private static Index<Keyword,ACell> retainFollowingExtensions(Index<Keyword,ACell> target,Index<Keyword,ACell> source) {
		if (source==null) return target;
		return target.mergeDifferences(source,new MergeFunction<ACell>() {
			@Override public ACell merge(ACell own,ACell other) { return own==null?other:own; }
			@Override public ACell merge(Object key,ACell own,ACell other) {
				return key instanceof ACell cell && FOLLOWING_STRUCTURE.path(cell)!=null?own:merge(own,other);
			}
		});
	}

	/** Guard only the fields the following merge actually examines; everything else is opaque. */
	private static boolean canMergeFollowing(Index<Keyword,ACell> value) {
		if (value==null) return false;
		ACell time=value.get(LWWLattice.KEY_TIMESTAMP), follows=value.get(KEY_FOLLOWS);
		return (time==null || time instanceof AInteger) && (follows==null || follows instanceof AHashMap<?,?>);
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
		if (childKey instanceof convex.core.data.ABlobLike<?> key) {
			var blob=key.toBlob();
			if (blob.equals(KEY_FEED.toBlob())) return (ALattice<T>) FEED_LATTICE;
			if (blob.equals(KEY_PROFILE.toBlob())) return (ALattice<T>) LWWLattice.INSTANCE;
			if (blob.equals(KEY_FOLLOWING.toBlob())) return (ALattice<T>) FOLLOWING_LATTICE;
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
		Index<Blob,ACell> index=RT.ensureIndex(feed);
		return index==null?Index.none():index;
	}

	/**
	 * Gets the profile from user state.
	 */
	@SuppressWarnings("unchecked")
	public static AHashMap<Keyword, ACell> getProfile(Index<Keyword, ACell> state) {
		if (state == null) return Maps.empty();
		ACell profile = state.get(KEY_PROFILE);
		if (profile == null) return Maps.empty();
		AHashMap<Keyword,ACell> map=RT.ensureHashMap(profile);
		return map==null?Maps.empty():map;
	}

	/**
	 * Gets the DID-keyed follows map from the {@code :following} section.
	 */
	@SuppressWarnings("unchecked")
	public static AHashMap<ACell, ACell> getFollows(Index<Keyword, ACell> state) {
		Index<Keyword,ACell> following=getFollowing(state);
		ACell follows = following.get(KEY_FOLLOWS);
		if (follows == null) return Maps.empty();
		AHashMap<ACell,ACell> map=RT.ensureHashMap(follows);
		return map==null?Maps.empty():map;
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

	@SuppressWarnings("unchecked")
	private static long recordTimestamp(ACell value) {
		if (!(value instanceof AHashMap<?,?> map)) return 0;
		ACell timestamp=((AHashMap<Keyword,ACell>)map).get(LWWLattice.KEY_TIMESTAMP);
		return (timestamp instanceof CVMLong l)?l.longValue():0;
	}

	/** Map boundary which drops malformed target DIDs and follow records. */
	private static final class DIDFollowMapLattice extends MapLattice<ACell,ACell> {
		DIDFollowMapLattice(ALattice<ACell> valueNode) {
			super(valueNode);
		}

		/** Validate only changed foreign records through the standard map merge. */
		@Override
		protected boolean checkEntry(ACell key,ACell value) {
			if (!(key instanceof convex.core.data.AString did)
					|| !convex.auth.did.DID.isCanonicalBase(did)) return false;
			if (!(value instanceof AHashMap<?,?> record)) return false;
			AHashMap<Keyword,ACell> typed=(AHashMap<Keyword,ACell>)record;
			if (!(typed.get(SocialPost.ACTIVE) instanceof CVMBool)
					|| !(typed.get(LWWLattice.KEY_TIMESTAMP) instanceof CVMLong)) return false;
			ACell cached=typed.get(Follows.KEY_ACCOUNT_KEY);
			return cached==null || convex.core.data.AccountKey.parse(cached)!=null;
		}
	}
}
