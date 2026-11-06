package convex.core.store;

/**
 * Immutable snapshot of cache counters for an {@link ACachedStore}.
 *
 * Counters reflect cache lookups: each call to {@link ACachedStore#decode(convex.core.data.Blob)}
 * increments exactly one of l1Hits or decodes, each call to
 * {@link ACachedStore#decodeStored(convex.core.data.Blob)} (a read from storage after a miss)
 * increments decodes, and each cache hit from
 * {@link ACachedStore#checkCache(convex.core.data.Hash)} increments l1Hits.
 */
public final class CacheStats {
	public final long l1Hits;
	public final long decodes;

	CacheStats(long l1Hits, long decodes) {
		this.l1Hits = l1Hits;
		this.decodes = decodes;
	}

	public long total() {
		return l1Hits + decodes;
	}

	public double hitRate() {
		long t = total();
		return (t == 0) ? 0.0 : (double) l1Hits / t;
	}

	@Override
	public String toString() {
		long t = total();
		return String.format("CacheStats[l1=%d, decodes=%d, total=%d, hitRate=%.3f]",
				l1Hits, decodes, t, hitRate());
	}
}
