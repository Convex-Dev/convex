package convex.social;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import convex.core.data.prim.CVMBool;

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
	 * @return Set of canonical base DIDs
	 */
	@SuppressWarnings("unchecked")
	public static Set<AString> getActiveFollows(AHashMap<ACell, ACell> follows) {
		Set<AString> result = new HashSet<>();
		if (follows == null || follows.isEmpty()) return result;

		long n = follows.count();
		for (long i = 0; i < n; i++) {
			MapEntry<ACell, ACell> entry = follows.entryAt(i);
			ACell key = entry.getKey();
			ACell value = entry.getValue();

			if (value instanceof AHashMap<?,?> record) {
				ACell active = ((AHashMap<Keyword, ACell>) record).get(SocialPost.ACTIVE);
				if (CVMBool.TRUE.equals(active) && key instanceof AString did
						&& convex.auth.did.DID.isCanonicalBase(did)) {
					result.add(did);
				}
			}
		}
		return result;
	}

	/**
	 * Computes the union of all actively followed keys across multiple local users.
	 *
	 * @param socialValue The social lattice value
	 * @param localUserDids set of local user DIDs
	 * @return combined set of all directly followed DIDs
	 */
	@SuppressWarnings("unchecked")
	public static Set<AString> computeFollowSet(
			AHashMap<ACell, SignedData<Index<Keyword, ACell>>> socialValue,
			Set<AString> localUserDids) {
		Set<AString> result = new HashSet<>();
		if (socialValue == null) return result;

		for (AString userDid : localUserDids) {
			Index<Keyword, ACell> userState = getUserState(socialValue, userDid);
			if (userState == null) continue;
			AHashMap<ACell, ACell> follows = SocialLattice.getFollows(userState);
			result.addAll(getActiveFollows(follows));
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
	 * Uses a K-way merge; tombstones and unfamiliar post/key shapes are skipped.
	 *
	 * <p>Pass the last returned entry's position for exact continuation, or a
	 * key-prefix position to skip a whole group. The bound need not exist in the
	 * feeds. Pagination over the same immutable feeds has no gaps or duplicates;
	 * changing feeds between calls does not provide snapshot isolation.</p>
	 *
	 * @param feeds Map of author DID to feed index
	 * @param before Exclusive position bound, or null to start at the newest entry
	 * @param limit Maximum number of entries to return
	 * @return Timeline entries in descending position order
	 */
	@SuppressWarnings("unchecked")
	public static List<TimelineEntry> buildTimeline(
			Map<AString, Index<Blob, ACell>> feeds,
			TimelinePosition before, int limit) {

		if (feeds == null || feeds.isEmpty() || limit <= 0) {
			return Collections.emptyList();
		}

		// Use exactly the same total order for selection and continuation.
		PriorityQueue<FeedCursor> heap = new PriorityQueue<>((a, b) ->
			b.order.compareTo(a.order));

		// Seed the heap with the last entry from each feed
		for (Map.Entry<AString, Index<Blob, ACell>> e : feeds.entrySet()) {
			AString author = e.getKey();
			Index<Blob, ACell> feed = e.getValue();
			if (feed == null || feed.isEmpty()) continue;

			FeedCursor first=previousEntry(author,feed,feed.count()-1,before);
			if (first!=null) heap.offer(first);
		}

		// Pull entries from the heap
		List<TimelineEntry> result = new ArrayList<>(Math.min(limit, 64));
		while (!heap.isEmpty() && result.size() < limit) {
			FeedCursor cursor = heap.poll();

			// Filter out tombstoned posts
			ACell value = cursor.entry.getValue();
			if (value instanceof AHashMap<?,?> post) {
				AHashMap<Keyword, ACell> postMap = (AHashMap<Keyword, ACell>) post;
				if (!SocialPost.isDeleted(postMap)) {
					result.add(new TimelineEntry(
						cursor.order.author(), cursor.order.postKey(),
						SocialPost.extractTimestamp(cursor.order.postKey()), postMap));
				}
			}

			// Advance cursor to previous entry
			FeedCursor previous=previousEntry(cursor.order.author(),cursor.feed,cursor.position-1,null);
			if (previous!=null) heap.offer(previous);
		}

		return result;
	}

	/** Skip unfamiliar keys before they can affect the timeline's chronological ordering. */
	private static FeedCursor previousEntry(AString author,Index<Blob,ACell> feed,long position,TimelinePosition before) {
		for (long i=position;i>=0;i--) {
			MapEntry<Blob,ACell> entry=feed.entryAt(i);
			ACell rawKey=entry.getKey();
			if (!(rawKey instanceof Blob key) || key.count()<8) continue;
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
