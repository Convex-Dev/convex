package convex.social;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.ABlob;
import convex.core.data.ASet;
import convex.core.data.Blob;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.data.prim.CVMBool;
import convex.core.util.Utils;

/**
 * Static helpers for creating and inspecting social feed posts.
 *
 * <p>Post format: {@code AHashMap<Keyword, ACell>} with keys:</p>
 * <ul>
 *   <li>{@code :text} — AString (required)</li>
 *   <li>{@code :timestamp} — CVMLong millis since epoch (required)</li>
 *   <li>{@code :reply-to} — Blob, 8-byte key of parent post (optional)</li>
 *   <li>{@code :reply-did} — ABlob, owner key of parent author (optional)</li>
 *   <li>{@code :media} — AVector of Hash refs to :data lattice (optional)</li>
 *   <li>{@code :tags} — ASet of AString hashtags (optional)</li>
 *   <li>{@code :deleted} — CVMLong, deletion timestamp (optional, tombstone)</li>
 * </ul>
 *
 * <p>Feed keys are 8-byte big-endian timestamp Blobs giving chronological
 * lexicographic ordering in {@code Index}.</p>
 */
public class SocialPost {

	public static final Keyword TEXT = Keyword.intern("text");
	public static final Keyword TIMESTAMP = Keyword.intern("timestamp");
	public static final Keyword REPLY_TO = Keyword.intern("reply-to");
	public static final Keyword REPLY_DID = Keyword.intern("reply-did");
	public static final Keyword MEDIA = Keyword.intern("media");
	public static final Keyword TAGS = Keyword.intern("tags");
	public static final Keyword DELETED = Keyword.intern("deleted");

	/**
	 * Creates an 8-byte feed key from a timestamp.
	 * Big-endian encoding gives chronological lexicographic ordering.
	 *
	 * @param timestampMillis Timestamp in milliseconds since epoch
	 * @return 8-byte Blob key
	 */
	public static Blob createKey(long timestampMillis) {
		byte[] key = new byte[8];
		Utils.writeLong(key, 0, timestampMillis);
		return Blob.wrap(key);
	}

	/**
	 * Extracts the timestamp from a feed key.
	 *
	 * @param key 8-byte Blob feed key
	 * @return Timestamp in milliseconds since epoch
	 */
	public static long extractTimestamp(Blob key) {
		return key.longAt(0);
	}

	/**
	 * Creates a post record.
	 *
	 * @param text Post text content
	 * @param timestamp Timestamp in milliseconds since epoch
	 * @return Post as AHashMap
	 */
	public static AHashMap<Keyword, ACell> createPost(String text, long timestamp) {
		return Maps.of(
			TEXT, Strings.create(text),
			TIMESTAMP, CVMLong.create(timestamp)
		);
	}

	/**
	 * Creates a reply post record.
	 *
	 * @param text Post text content
	 * @param timestamp Timestamp in milliseconds since epoch
	 * @param parentKey 8-byte key of the parent post
	 * @param parentDid Owner key of the parent post's author
	 * @return Reply post as AHashMap
	 */
	public static AHashMap<Keyword, ACell> createReply(String text, long timestamp,
			Blob parentKey, ABlob parentDid) {
		return Maps.of(
			TEXT, Strings.create(text),
			TIMESTAMP, CVMLong.create(timestamp),
			REPLY_TO, parentKey,
			REPLY_DID, parentDid
		);
	}

	/**
	 * Checks if a post has been tombstone-deleted.
	 *
	 * @param post Post record
	 * @return true if the post has a :deleted field
	 */
	public static boolean isDeleted(AHashMap<Keyword, ACell> post) {
		return post.get(DELETED) != null;
	}

	/**
	 * Gets the text content of a post.
	 */
	public static String getText(AHashMap<Keyword, ACell> post) {
		ACell text = post.get(TEXT);
		return (text != null) ? text.toString() : null;
	}

	/**
	 * Gets the timestamp of a post.
	 */
	public static long getTimestamp(AHashMap<Keyword, ACell> post) {
		ACell ts = post.get(TIMESTAMP);
		if (ts instanceof CVMLong l) return l.longValue();
		return 0;
	}

	// ===== Follow helpers =====

	public static final Keyword ACTIVE = Keyword.intern("active");

	/**
	 * Creates a follow record for adding/toggling a follow.
	 *
	 * @param timestamp Timestamp of the action
	 * @param active true to follow, false to unfollow
	 * @return Follow record as AHashMap
	 */
	public static AHashMap<Keyword, ACell> createFollowRecord(long timestamp, boolean active) {
		return Maps.of(
			TIMESTAMP, CVMLong.create(timestamp),
			ACTIVE, CVMBool.of(active)
		);
	}

	/**
	 * Checks if a follow record indicates an active follow.
	 */
	public static boolean isActiveFollow(AHashMap<Keyword, ACell> followRecord) {
		if (followRecord == null) return false;
		ACell active = followRecord.get(ACTIVE);
		if (active instanceof CVMBool b) return b.booleanValue();
		return false;
	}
}
