package convex.social;

import java.security.SecureRandom;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
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
 *   <li>{@code :reply-to} — Blob, key of parent post (optional)</li>
	 *   <li>{@code :reply-did} — AString, stable DID of parent author (optional)</li>
 *   <li>{@code :media} — AVector of Hash refs to :data lattice (optional)</li>
 *   <li>{@code :tags} — ASet of AString hashtags (optional)</li>
 *   <li>{@code :deleted} — CVMLong, deletion timestamp (optional, tombstone)</li>
 * </ul>
 *
 * <p>Feed keys contain an eight-byte big-endian timestamp followed by eight random
 * bytes. The timestamp prefix preserves chronological index ordering; the salt
 * separates independent writers. Existing eight-byte keys remain readable.</p>
 */
public class SocialPost {

	private static final SecureRandom KEY_RANDOM=new SecureRandom();

	public static final Keyword TEXT = Keyword.intern("text");
	public static final Keyword TIMESTAMP = Keyword.intern("timestamp");
	public static final Keyword REPLY_TO = Keyword.intern("reply-to");
	public static final Keyword REPLY_DID = Keyword.intern("reply-did");
	public static final Keyword MEDIA = Keyword.intern("media");
	public static final Keyword TAGS = Keyword.intern("tags");
	public static final Keyword DELETED = Keyword.intern("deleted");

	/** Creates a timestamp-prefixed key with an independent 64-bit random salt. */
	public static Blob createKey(long timestampMillis) {
		return createKey(timestampMillis,KEY_RANDOM.nextLong());
	}

	/** Creates a key with an explicit salt, for deterministic fixtures or caller-owned allocation. */
	public static Blob createKey(long timestampMillis,long salt) {
		byte[] key=new byte[16];
		Utils.writeLong(key,0,timestampMillis);
		Utils.writeLong(key,8,salt);
		return Blob.wrap(key);
	}

	/**
	 * Extracts the timestamp from a feed key.
	 *
	 * @param key Timestamp-prefixed feed key, including legacy eight-byte keys
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
	 * @param parentKey key of the parent post
	 * @param parentDid Owner key of the parent post's author
	 * @return Reply post as AHashMap
	 */
	public static AHashMap<Keyword, ACell> createReply(String text, long timestamp,
			Blob parentKey, AString parentDid) {
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
		CVMLong deleted=post==null?null:(CVMLong)post.get(DELETED);
		return deleted!=null;
	}

	/**
	 * Returns the text content, or null if absent. Incompatible values throw.
	 */
	public static String getText(AHashMap<Keyword, ACell> post) {
		AString text=post==null?null:(AString)post.get(TEXT);
		return text==null?null:text.toString();
	}

	/**
	 * Returns the timestamp, or null if absent. Incompatible values throw.
	 */
	public static Long getTimestamp(AHashMap<Keyword, ACell> post) {
		CVMLong timestamp=post==null?null:(CVMLong)post.get(TIMESTAMP);
		return timestamp==null?null:timestamp.longValue();
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
	 * Returns false for an absent record; an existing record must contain a boolean active flag.
	 */
	public static boolean isActiveFollow(AHashMap<Keyword, ACell> followRecord) {
		if (followRecord==null) return false;
		CVMBool active=(CVMBool)followRecord.get(ACTIVE);
		if (active==null) throw new IllegalStateException("Follow record requires an active flag");
		return active.booleanValue();
	}
}
