package convex.core.store;

import java.util.concurrent.atomic.LongAdder;

import convex.core.cvm.CVMEncoder;
import convex.core.data.ACell;
import convex.core.data.AEncoder;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.exceptions.BadFormatException;
import convex.core.util.SoftCache;

/**
 * Abstract base class for stores implementing an in-memory cache of cells.
 *
 * <p>Two-tier cache:</p>
 * <ul>
 *   <li>L1: small fixed-size array probe ({@link RefCache}). Strong refs to {@link Ref}
 *       wrappers, holding {@link convex.core.data.RefSoft} to cells. Fast hit path.</li>
 *   <li>L2: optional {@link SoftCache} keyed by content hash. Soft refs to cells, unbounded
 *       by entry count, cleared by GC under heap pressure. Catches L1 collision-evictions and
 *       avoids redundant subtree decoding.</li>
 * </ul>
 *
 * Counters track which tier serviced each {@link #decode(Blob)} call so callers can
 * measure hit rates and the impact of disabling L2.
 */
public abstract class ACachedStore extends AStore {

	protected final RefCache refCache=RefCache.create(10000);

	/**
	 * Optional L2 cache. May be null (disabled). Maps content hash to the decoded cell;
	 * the SoftCache wraps each value in a SoftReference so entries are reclaimable.
	 */
	protected final SoftCache<Hash, ACell> softCache;

	/**
	 * Store-bound encoder. Manages thread-local store context during decode.
	 */
	protected final CVMEncoder encoder=new CVMEncoder(this);

	private final LongAdder l1Hits = new LongAdder();
	private final LongAdder l2Hits = new LongAdder();
	private final LongAdder decodes = new LongAdder();

	protected ACachedStore() {
		this(true);
	}

	protected ACachedStore(boolean enableL2) {
		this.softCache = enableL2 ? new SoftCache<>() : null;
	}

	@Override
	public AEncoder<ACell> getEncoder() {
		return encoder;
	}

	@Override
	@SuppressWarnings("unchecked")
	public final <T extends ACell> T decode(Blob encoding) throws BadFormatException {
		Hash hash=encoding.getContentHash();

		// L1: array probe
		Ref<?> cached= refCache.getCell(hash);
		if (cached!=null) {
			l1Hits.increment();
			return (T) cached.getValue();
		}

		// L2: soft cache
		if (softCache != null) {
			ACell hit = softCache.get(hash);
			if (hit != null) {
				l2Hits.increment();
				refCache.putCell(hit); // promote to L1
				return (T) hit;
			}
		}

		// Miss: full decode
		ACell decoded = encoder.decode(encoding);
		decodes.increment();
		refCache.putCell(decoded);
		if (softCache != null) {
			softCache.put(hash, decoded);
		}
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
		if (softCache != null) {
			ACell cell = softCache.get(h);
			if (cell != null) {
				l2Hits.increment();
				refCache.putCell(cell); // promote to L1
				return (Ref<T>) cell.getRef();
			}
		}
		return null;
	}

	/**
	 * Records a cell retrieved directly from backing storage (bypassing decode cache lookup)
	 * and populates both L1 and L2. Increments the decode counter — semantically this was
	 * a cache miss that required reconstructing the cell from its encoding.
	 *
	 * @param hash Content hash of the retrieved cell
	 * @param ref Ref wrapping the freshly-decoded cell
	 */
	protected void cacheRetrievedRef(Hash hash, Ref<?> ref) {
		decodes.increment();
		refCache.putCell(ref);
		if (softCache != null) {
			// Only populate L2 if absent — preserves instance stability for any
			// cell already in the cache. Cross-store users may rely on cell.cachedRef
			// pointing to the originally-attaching store.
			if (softCache.get(hash) == null) {
				ACell cell = ref.getValue();
				if (cell != null) softCache.put(hash, cell);
			}
		}
	}

	/**
	 * Returns whether this store has the L2 SoftCache enabled.
	 * @return true if L2 cache is in use
	 */
	public boolean isL2Enabled() {
		return softCache != null;
	}

	/**
	 * Returns an immutable snapshot of the cache counters.
	 * @return current cache statistics
	 */
	public CacheStats getCacheStats() {
		return new CacheStats(l1Hits.sum(), l2Hits.sum(), decodes.sum());
	}

	/**
	 * Resets all cache counters to zero.
	 */
	public void resetCacheStats() {
		l1Hits.reset();
		l2Hits.reset();
		decodes.reset();
	}
}
