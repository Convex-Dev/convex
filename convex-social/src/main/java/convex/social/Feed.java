package convex.social;

import java.util.function.LongFunction;
import java.util.function.Supplier;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.ALatticeComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;

/**
 * Cursor wrapper for a user's post feed.
 *
 * <p>A {@code Feed} wraps a lattice cursor at the feed level
 * ({@code Index<Blob, ACell>} with timestamp-prefixed salted keys). All
 * writes propagate through the cursor chain and are automatically
 * signed at the {@code SignedCursor} boundary.</p>
 *
 * <pre>{@code
 * Feed feed = social.user(myKey).feed();
 * Blob key = feed.post("Hello, lattice world!");
 * AHashMap<Keyword, ACell> post = feed.getPost(key);
 * }</pre>
 */
public class Feed extends ALatticeComponent<Index<Blob, ACell>> implements TransactionalComponent<Index<Blob, ACell>,Feed> {
	private final Supplier<CVMLong> clock;


	private final AString author;

	Feed(AForkableCursor<Index<Blob, ACell>> cursor, AString author, Supplier<CVMLong> clock) {
		super(cursor);
		this.clock=clock;
		this.author = author;
	}

	/**
	 * Posts a new entry to the feed.
	 *
	 * @param text Post text content
	 * @return The timestamp-prefixed key assigned to the post
	 */
	public Blob post(String text) {
		return addEntry(ts -> SocialPost.createPost(text, ts));
	}

	/**
	 * Posts a reply to another post.
	 *
	 * @param text Reply text content
	 * @param parentKey key of the parent post
	 * @param parentAuthor Account key of the parent post's author
	 * @return The timestamp-prefixed key assigned to this reply
	 */
	public Blob reply(String text, Blob parentKey, AccountKey parentAuthor) {
		return reply(text,parentKey,convex.auth.did.DID.forKey(parentAuthor));
	}

	/** Posts a reply addressed to the stable DID of the parent author. */
	public Blob reply(String text, Blob parentKey, AString parentAuthor) {
		if (!convex.auth.did.DID.isCanonicalBase(parentAuthor)) {
			throw new IllegalArgumentException("Parent author must be a canonical base DID");
		}
		return addEntry(ts -> SocialPost.createReply(text, ts, parentKey, parentAuthor));
	}

	/** Allocates identity and time once, preserving both across atomic update retries. */
	private Blob addEntry(LongFunction<AHashMap<Keyword, ACell>> entryFn) {
		long timestamp=clock.get().longValue();
		AHashMap<Keyword,ACell> entry=entryFn.apply(timestamp);
		Blob key=SocialPost.createKey(timestamp);
		cursor().updateAndGet(feed -> {
			if (feed==null) feed=SocialLattice.FEED_LATTICE.zero();
			// Even an exceptionally unlikely salt collision must never overwrite a post.
			if (feed.containsKey(key)) throw new IllegalStateException("Post key already exists");
			return feed.assoc(key,entry);
		});
		return key;
	}

	/**
	 * Deletes a post by adding a tombstone.
	 *
	 * <p>The post is not removed — a {@code :deleted} timestamp is added
	 * and the {@code :timestamp} is updated so LWW merge picks the
	 * deletion. Timeline construction filters out tombstoned posts.</p>
	 *
	 * @param postKey key of the post to delete
	 */
	@SuppressWarnings("unchecked")
	public void delete(Blob postKey) {
		CVMLong now = clock.get();
		cursor().updateAndGet(feed -> {
			if (feed == null) return feed;
			ACell existing = feed.get(postKey);
			if (!(existing instanceof AHashMap<?,?>)) return feed;
			AHashMap<Keyword, ACell> post = (AHashMap<Keyword, ACell>) existing;
			post = post.assoc(SocialPost.DELETED, now);
			post = post.assoc(SocialPost.TIMESTAMP, now);
			return feed.assoc(postKey, post);
		});
	}

	/**
	 * Gets a post by key.
	 *
	 * @param key timestamp-prefixed key
	 * @return The post record, or null if not found
	 */
	@SuppressWarnings("unchecked")
	public AHashMap<Keyword, ACell> getPost(Blob key) {
		Index<Blob, ACell> feed = RT.ensureIndex(cursor().get());
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
		Index<Blob, ACell> feed = RT.ensureIndex(cursor().get());
		return (feed != null) ? feed.count() : 0;
	}

	/**
	 * Gets the author's account key.
	 *
	 * @return The feed owner's key
	 */
	public AString getAuthor() {
		return author;
	}

	@Override
	public Feed withCursor(AForkableCursor<Index<Blob, ACell>> cursor) {
		return new Feed(cursor,author,clock);
	}

}
