package convex.core.store;

import java.util.concurrent.atomic.LongAdder;

import convex.core.cvm.CVMEncoder;
import convex.core.data.ACell;
import convex.core.data.AEncoder;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Format;
import convex.core.data.Ref;
import convex.core.exceptions.BadFormatException;

/**
 * Abstract base class for stores implementing an in-memory cache of cells.
 *
 * <p>The cache is a fixed-size array probe ({@link RefCache}) holding {@link Ref}
 * wrappers strongly: direct refs for cells decoded but not yet stored, store-owned
 * {@link convex.core.data.RefSoft} for stored cells. A cell evicted from it is read
 * or decoded again when next needed. Beyond the cache, stored cells stay in memory
 * only through the soft refs of whatever holds them.</p>
 *
 * Counters track whether the cache served each lookup, so callers can measure hit
 * rates.
 */
public abstract class ACachedStore extends AStore {

	/** Default number of reference-cache slots. */
	public static final int DEFAULT_REF_CACHE_SIZE=10000;

	protected final RefCache refCache;

	/**
	 * Encoder whose branch references resolve against this store.
	 */
	protected final CVMEncoder encoder=new CVMEncoder(this);

	private final LongAdder l1Hits = new LongAdder();
	private final LongAdder decodes = new LongAdder();

	protected ACachedStore() {
		this(DEFAULT_REF_CACHE_SIZE);
	}

	protected ACachedStore(int refCacheSize) {
		this.refCache = RefCache.create(this, refCacheSize);
	}

	@Override
	public AEncoder<ACell> getEncoder() {
		return encoder;
	}

	@Override
	@SuppressWarnings("unchecked")
	public final <T extends ACell> T decode(Blob encoding) throws BadFormatException {
		// Never hash embedded bytes just to probe the cache. Reuse an existing
		// hash when available, including explicitly persisted embedded roots.
		boolean embedded=encoding.count()<=Format.MAX_EMBEDDED_LENGTH;
		Hash hash=embedded?encoding.cachedContentHash():encoding.getContentHash();
		if (hash==null) {
			ACell decoded=encoder.decode(encoding);
			decodes.increment();
			return (T) decoded;
		}

		Ref<?> cached= refCache.getCell(hash);
		if (cached!=null) {
			l1Hits.increment();
			return (T) CVMEncoder.specialise(cached.getValue());
		}

		// Miss: full decode
		ACell decoded = encoder.decode(encoding);
		decodes.increment();
		if (embedded) return (T) decoded; // no automatic admission of embedded values
		refCache.putDecoded(decoded);
		return (T)decoded;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> Ref<T> checkCache(Hash h) {
		Ref<?> ref = refCache.getCell(h);
		if (ref != null) {
			l1Hits.increment();
			return (Ref<T>) ref;
		}
		return null;
	}

	/** Returns the number of slots allocated to the reference cache. */
	public int getRefCacheSize() {
		return refCache.getSize();
	}

	/**
	 * Returns an immutable snapshot of the cache counters.
	 * @return current cache statistics
	 */
	public CacheStats getCacheStats() {
		return new CacheStats(l1Hits.sum(), decodes.sum());
	}

	/**
	 * Resets all cache counters to zero.
	 */
	public void resetCacheStats() {
		l1Hits.reset();
		decodes.reset();
	}
}
