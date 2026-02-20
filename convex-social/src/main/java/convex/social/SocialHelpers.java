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
import convex.core.data.AccountKey;
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
	 * @param userKey The user's account key
	 * @return The user's social state, or null if not present
	 */
	@SuppressWarnings("unchecked")
	public static Index<Keyword, ACell> getUserState(
			AHashMap<ACell, SignedData<Index<Keyword, ACell>>> socialValue,
			AccountKey userKey) {
		if (socialValue == null) return null;
		SignedData<Index<Keyword, ACell>> signed = socialValue.get(userKey);
		if (signed == null) return null;
		return signed.getValue();
	}

	/**
	 * Gets the set of actively followed account keys from a user's follows map.
	 *
	 * @param follows The follows map (key → {active, timestamp})
	 * @return Set of actively followed AccountKeys
	 */
	@SuppressWarnings("unchecked")
	public static Set<AccountKey> getActiveFollows(AHashMap<ACell, ACell> follows) {
		Set<AccountKey> result = new HashSet<>();
		if (follows == null || follows.isEmpty()) return result;

		long n = follows.count();
		for (long i = 0; i < n; i++) {
			MapEntry<ACell, ACell> entry = follows.entryAt(i);
			ACell key = entry.getKey();
			ACell value = entry.getValue();

			if (value instanceof AHashMap<?,?> record) {
				ACell active = ((AHashMap<Keyword, ACell>) record).get(SocialPost.ACTIVE);
				if (CVMBool.TRUE.equals(active) && key instanceof AccountKey ak) {
					result.add(ak);
				}
			}
		}
		return result;
	}

	/**
	 * Computes the union of all actively followed keys across multiple local users.
	 *
	 * @param socialValue The social lattice value
	 * @param localUserKeys Set of local user account keys
	 * @return Combined set of all followed account keys
	 */
	@SuppressWarnings("unchecked")
	public static Set<AccountKey> computeFollowSet(
			AHashMap<ACell, SignedData<Index<Keyword, ACell>>> socialValue,
			Set<AccountKey> localUserKeys) {
		Set<AccountKey> result = new HashSet<>();
		if (socialValue == null) return result;

		for (AccountKey userKey : localUserKeys) {
			Index<Keyword, ACell> userState = getUserState(socialValue, userKey);
			if (userState == null) continue;
			AHashMap<ACell, ACell> follows = SocialLattice.getFollows(userState);
			result.addAll(getActiveFollows(follows));
		}
		return result;
	}

	/**
	 * Builds a merged timeline from multiple user feeds, newest first.
	 *
	 * <p>Uses a K-way merge with a max-heap to efficiently merge feeds
	 * in reverse chronological order. Tombstoned posts (with :deleted field)
	 * are filtered out.</p>
	 *
	 * @param feeds Map of author → feed index
	 * @param beforeTimestamp Only include posts before this timestamp (0 for no limit)
	 * @param limit Maximum number of entries to return
	 * @return List of timeline entries, newest first
	 */
	@SuppressWarnings("unchecked")
	public static List<TimelineEntry> buildTimeline(
			Map<AccountKey, Index<Blob, ACell>> feeds,
			long beforeTimestamp, int limit) {

		if (feeds == null || feeds.isEmpty() || limit <= 0) {
			return Collections.emptyList();
		}

		// Max-heap by timestamp (newest first)
		PriorityQueue<FeedCursor> heap = new PriorityQueue<>((a, b) ->
			Long.compare(b.timestamp, a.timestamp));

		// Seed the heap with the last entry from each feed
		for (Map.Entry<AccountKey, Index<Blob, ACell>> e : feeds.entrySet()) {
			AccountKey author = e.getKey();
			Index<Blob, ACell> feed = e.getValue();
			if (feed == null || feed.isEmpty()) continue;

			long feedSize = feed.count();
			// Find the starting position: last entry, or search backwards from beforeTimestamp
			long pos = feedSize - 1;
			if (beforeTimestamp > 0) {
				// Scan backwards to find first entry before the cursor
				while (pos >= 0) {
					MapEntry<Blob, ACell> entry = feed.entryAt(pos);
					long ts = SocialPost.extractTimestamp(entry.getKey());
					if (ts < beforeTimestamp) break;
					pos--;
				}
			}
			if (pos >= 0) {
				MapEntry<Blob, ACell> entry = feed.entryAt(pos);
				long ts = SocialPost.extractTimestamp(entry.getKey());
				heap.offer(new FeedCursor(author, feed, pos, entry, ts));
			}
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
						cursor.author, cursor.entry.getKey(),
						cursor.timestamp, postMap));
				}
			}

			// Advance cursor to previous entry
			long prevPos = cursor.position - 1;
			if (prevPos >= 0) {
				MapEntry<Blob, ACell> prevEntry = cursor.feed.entryAt(prevPos);
				long prevTs = SocialPost.extractTimestamp(prevEntry.getKey());
				heap.offer(new FeedCursor(cursor.author, cursor.feed,
					prevPos, prevEntry, prevTs));
			}
		}

		return result;
	}

	/**
	 * Internal cursor for tracking position in a feed during K-way merge.
	 */
	private record FeedCursor(
		AccountKey author,
		Index<Blob, ACell> feed,
		long position,
		MapEntry<Blob, ACell> entry,
		long timestamp
	) {}
}
