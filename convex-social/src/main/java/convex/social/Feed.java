package convex.social;

import java.util.function.LongFunction;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.ALatticeCursor;

/**
 * Cursor wrapper for a user's post feed.
 *
 * <p>A {@code Feed} wraps a lattice cursor at the feed level
 * ({@code Index<Blob, ACell>} with 8-byte timestamp keys). All
 * writes propagate through the cursor chain and are automatically
 * signed at the {@code SignedCursor} boundary.</p>
 *
 * <pre>{@code
 * Feed feed = social.user(myKey).feed();
 * Blob key = feed.post("Hello, lattice world!");
 * AHashMap<Keyword, ACell> post = feed.getPost(key);
 * }</pre>
 */
public class Feed extends ALatticeComponent<Index<Blob, ACell>> {

	private final AccountKey author;

	Feed(ALatticeCursor<Index<Blob, ACell>> cursor, AccountKey author) {
		super(cursor);
		this.author = author;
	}

	/**
	 * Posts a new entry to the feed.
	 *
	 * @param text Post text content
	 * @return The 8-byte timestamp key assigned to the post
	 */
	public Blob post(String text) {
		return addEntry(ts -> SocialPost.createPost(text, ts));
	}

	/**
	 * Posts a reply to another post.
	 *
	 * @param text Reply text content
	 * @param parentKey 8-byte key of the parent post
	 * @param parentAuthor Account key of the parent post's author
	 * @return The 8-byte timestamp key assigned to this reply
	 */
	public Blob reply(String text, Blob parentKey, AccountKey parentAuthor) {
		return addEntry(ts -> SocialPost.createReply(text, ts, parentKey, parentAuthor));
	}

	/**
	 * Adds a new entry to the feed under a unique timestamp key.
	 *
	 * <p>Keys are millisecond timestamps, so two entries created in the same
	 * millisecond would otherwise collide and the later one silently overwrite
	 * the earlier (LWW data loss). On collision the timestamp is bumped until a
	 * free key is found — a new entry never replaces an existing one. The check
	 * runs inside the atomic cursor update, so it holds across Feed instances
	 * and concurrent posts on the same cursor.
	 *
	 * @param entryFn Creates the entry record for the (possibly bumped) timestamp
	 * @return The 8-byte timestamp key assigned to the entry
	 */
	private Blob addEntry(LongFunction<AHashMap<Keyword, ACell>> entryFn) {
		Blob[] keyHolder = new Blob[1];
		cursor.updateAndGet(feed -> {
			long ts = System.currentTimeMillis();
			Blob key = SocialPost.createKey(ts);
			while (feed != null && feed.get(key) != null) {
				ts++;
				key = SocialPost.createKey(ts);
			}
			keyHolder[0] = key;
			AHashMap<Keyword, ACell> entry = entryFn.apply(ts);
			return feed.assoc(key, entry);
		});
		return keyHolder[0];
	}

	/**
	 * Deletes a post by adding a tombstone.
	 *
	 * <p>The post is not removed — a {@code :deleted} timestamp is added
	 * and the {@code :timestamp} is updated so LWW merge picks the
	 * deletion. Timeline construction filters out tombstoned posts.</p>
	 *
	 * @param postKey 8-byte key of the post to delete
	 */
	@SuppressWarnings("unchecked")
	public void delete(Blob postKey) {
		long ts = System.currentTimeMillis();
		CVMLong now = CVMLong.create(ts);
		cursor.updateAndGet(feed -> {
			if (feed == null) return feed;
			ACell existing = feed.get(postKey);
			if (existing == null) return feed;
			AHashMap<Keyword, ACell> post = (AHashMap<Keyword, ACell>) existing;
			post = post.assoc(SocialPost.DELETED, now);
			post = post.assoc(SocialPost.TIMESTAMP, now);
			return feed.assoc(postKey, post);
		});
	}

	/**
	 * Gets a post by key.
	 *
	 * @param key 8-byte timestamp key
	 * @return The post record, or null if not found
	 */
	@SuppressWarnings("unchecked")
	public AHashMap<Keyword, ACell> getPost(Blob key) {
		Index<Blob, ACell> feed = cursor.get();
		if (feed == null) return null;
		ACell value = feed.get(key);
		return (value instanceof AHashMap) ? (AHashMap<Keyword, ACell>) value : null;
	}

	/**
	 * Returns the number of entries in the feed (including tombstones).
	 *
	 * @return Entry count
	 */
	public long count() {
		Index<Blob, ACell> feed = cursor.get();
		return (feed != null) ? feed.count() : 0;
	}

	/**
	 * Gets the author's account key.
	 *
	 * @return The feed owner's key
	 */
	public AccountKey getAuthor() {
		return author;
	}

}
