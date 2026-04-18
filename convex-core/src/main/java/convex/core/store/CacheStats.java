package convex.core.store;

/**
 * Immutable snapshot of cache counters for an {@link ACachedStore}.
 *
 * Counters reflect calls to {@link ACachedStore#decode(convex.core.data.Blob)}:
 * exactly one of l1Hits, l2Hits, or decodes is incremented per call.
 */
public final class CacheStats {
	public final long l1Hits;
	public final long l2Hits;
	public final long decodes;

	CacheStats(long l1Hits, long l2Hits, long decodes) {
		this.l1Hits = l1Hits;
		this.l2Hits = l2Hits;
		this.decodes = decodes;
	}

	public long total() {
		return l1Hits + l2Hits + decodes;
	}

	public double hitRate() {
		long t = total();
		return (t == 0) ? 0.0 : (double) (l1Hits + l2Hits) / t;
	}

	@Override
	public String toString() {
		long t = total();
		return String.format("CacheStats[l1=%d, l2=%d, decodes=%d, total=%d, hitRate=%.3f]",
				l1Hits, l2Hits, decodes, t, hitRate());
	}
}
