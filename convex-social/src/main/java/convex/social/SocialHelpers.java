package convex.social;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.MapEntry;
import convex.core.data.SignedData;

/**
 * Helper methods for the social lattice.
 *
 * <p>Provides timeline construction, follow-set computation, and
 * convenience accessors for social lattice state.</p>
 */
public class SocialHelpers {

	/**
	 * Gets a user's social state from the social lattice value.
	 *
	 * @param socialValue The social lattice value (owner → SignedData)
	 * @param userDid the user's canonical base DID
	 * @return The user's social state, or null if not present
	 */
	@SuppressWarnings("unchecked")
	public static Index<Keyword, ACell> getUserState(
			AHashMap<ACell, SignedData<Index<Keyword, ACell>>> socialValue,
			AString userDid) {
		if (socialValue == null) return null;
		SignedData<Index<Keyword, ACell>> signed = socialValue.get(userDid);
		if (signed == null) return null;
		return signed.getValue();
	}

	/**
	 * Gets the set of actively followed DIDs from a user's follows map.
	 *
	 * @param follows The follows map (key → {active, timestamp})
	 * @return Set of canonical base DIDs, or null if the follows map is absent
	 */
	@SuppressWarnings("unchecked")
	public static Set<AString> getActiveFollows(AHashMap<ACell, ACell> follows) {
		if (follows==null) return null;
		Set<AString> result=new HashSet<>();
		for (var entry:follows.entrySet()) {
			if (!(entry.getKey() instanceof AString did) || !convex.auth.did.DID.isCanonicalBase(did)) {
				throw new IllegalArgumentException("Follow target must be a canonical base DID");
			}
			AHashMap<Keyword,ACell> record=(AHashMap<Keyword,ACell>)entry.getValue();
			if (record==null) throw new IllegalStateException("Follow entry requires a record");
			if (SocialPost.isActiveFollow(record)) result.add(did);
		}
		return result;
	}

	/**
	 * Computes the union of all actively followed keys across multiple local users.
	 *
	 * @param socialValue The social lattice value
	 * @param localUserDids set of local user DIDs
	 * @return Combined set of directly followed DIDs, or null if social state is absent
	 */
	@SuppressWarnings("unchecked")
	public static Set<AString> computeFollowSet(
			AHashMap<ACell, SignedData<Index<Keyword, ACell>>> socialValue,
			Set<AString> localUserDids) {
		Set<AString> result = new HashSet<>();
		if (socialValue == null) return null;

		for (AString userDid : localUserDids) {
			Index<Keyword, ACell> userState = getUserState(socialValue, userDid);
			if (userState == null) continue;
			AHashMap<ACell, ACell> follows = SocialLattice.getFollows(userState);
			if (follows!=null) result.addAll(getActiveFollows(follows));
		}
		return result;
	}

	/**
	 * Builds a merged timeline from multiple user feeds, newest first.
	 *
	 * <p>This coarse bound excludes every post at the timestamp. For exact
	 * continuation, pass the last entry's {@link TimelineEntry#position()} to
	 * {@link #buildTimeline(Map, TimelinePosition, int)}.</p>
	 *
	 * @param feeds Map of author → feed index
	 * @param beforeTimestamp Only include posts before this timestamp (0 for no limit)
	 * @param limit Maximum number of entries to return
	 * @return List of timeline entries, newest first
	 */
	public static List<TimelineEntry> buildTimeline(
			Map<AString, Index<Blob, ACell>> feeds,
			long beforeTimestamp, int limit) {
		return buildTimeline(feeds,beforeTimestamp>0?TimelinePosition.atTimestamp(beforeTimestamp):null,limit);
	}

	/**
	 * Builds a timeline in descending feed-key order, breaking ties by author DID.
	 * Uses a K-way merge; tombstones are excluded. Incompatible keys or post values throw.
	 *
	 * <p>Pass the last returned entry's position for exact continuation, or a
	 * key-prefix position to skip a whole group. The bound need not exist in the
	 * feeds. Pagination over the same immutable feeds has no gaps or duplicates;
	 * changing feeds between calls does not provide snapshot isolation.</p>
	 *
	 * @param feeds Map of author DID to feed index
	 * @param before Exclusive position bound, or null to start at the newest entry
	 * @param limit Maximum number of entries to return
	 * @return Timeline entries in descending position order, or null if feeds is null
	 */
	@SuppressWarnings("unchecked")
	public static List<TimelineEntry> buildTimeline(
			Map<AString, Index<Blob, ACell>> feeds,
			TimelinePosition before, int limit) {

		if (limit<0) throw new IllegalArgumentException("Timeline limit must not be negative");
		if (feeds==null) return null;
		if (feeds.isEmpty() || limit == 0) {
			return Collections.emptyList();
		}

		// Use exactly the same total order for selection and continuation.
		PriorityQueue<FeedCursor> heap = new PriorityQueue<>((a, b) ->
			b.order.compareTo(a.order));

		// Seek each feed directly to the bound instead of revisiting all newer posts.
		for (Map.Entry<AString, Index<Blob, ACell>> e : feeds.entrySet()) {
			AString author = e.getKey();
			Index<Blob, ACell> feed = e.getValue();
			if (feed == null || feed.isEmpty()) continue;

			long start=before==null?feed.count()-1:Math.min(feed.seek(before.postKey()),feed.count()-1);
			// Keep the lower-bound entry as a candidate: an equal key with an earlier
			// author is below the full position. previousEntry applies that final test.
			FeedCursor first=previousEntry(author,feed,start,before);
			if (first!=null) heap.offer(first);
		}

		// Pull entries from the heap
		List<TimelineEntry> result = new ArrayList<>(Math.min(limit, 64));
		while (!heap.isEmpty() && result.size() < limit) {
			FeedCursor cursor = heap.poll();

			// Filter out tombstoned posts
			AHashMap<Keyword,ACell> post=(AHashMap<Keyword,ACell>)cursor.entry.getValue();
			Objects.requireNonNull(post,"Feed entry requires a post record");
			if (!SocialPost.isDeleted(post)) {
				result.add(new TimelineEntry(cursor.order.author(),cursor.order.postKey(),
					SocialPost.extractTimestamp(cursor.order.postKey()),post));
			}

			if (result.size()==limit) break;

			// Advance cursor to previous entry
			FeedCursor previous=previousEntry(cursor.order.author(),cursor.feed,cursor.position-1,null);
			if (previous!=null) heap.offer(previous);
		}

		return result;
	}

	/** Finds the previous entry below the bound, requiring timestamp-prefixed blob keys. */
	private static FeedCursor previousEntry(AString author,Index<Blob,ACell> feed,long position,TimelinePosition before) {
		for (long i=position;i>=0;i--) {
			MapEntry<Blob,ACell> entry=feed.entryAt(i);
			ACell rawKey=entry.getKey();
			if (!(rawKey instanceof Blob key) || key.count()<8) {
				throw new IllegalArgumentException("Feed key must be a timestamp-prefixed blob");
			}
			TimelinePosition order=new TimelinePosition(key,author);
			if (before!=null && order.compareTo(before)>=0) continue;
			return new FeedCursor(order,feed,i,entry);
		}
		return null;
	}

	/**
	 * Internal cursor for tracking position in a feed during K-way merge.
	 */
	private record FeedCursor(
		TimelinePosition order,
		Index<Blob, ACell> feed,
		long position,
		MapEntry<Blob, ACell> entry
	) {}
}
