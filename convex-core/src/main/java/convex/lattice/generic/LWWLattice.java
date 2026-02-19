package convex.lattice.generic;

import java.util.function.ToLongFunction;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Keyword;
import convex.core.data.prim.CVMLong;

/**
 * Last-Write-Wins register lattice.
 *
 * <p>Merge picks the value with the higher timestamp, as extracted by a
 * caller-provided function. If timestamps are equal but values differ,
 * a deterministic tiebreaker (encoding hash comparison) ensures commutativity.</p>
 *
 * @param <V> Type of lattice values
 */
public class LWWLattice<V extends ACell> extends AValueLattice<V> {

	public static final Keyword KEY_TIMESTAMP = Keyword.intern("timestamp");

	private final ToLongFunction<V> timestampFn;

	private LWWLattice(ToLongFunction<V> timestampFn) {
		this.timestampFn = timestampFn;
	}

	/**
	 * Creates a LWW lattice with a custom timestamp extractor.
	 * @param <V> Value type
	 * @param timestampFn Function to extract a long timestamp from a value
	 * @return New LWWLattice instance
	 */
	public static <V extends ACell> LWWLattice<V> create(ToLongFunction<V> timestampFn) {
		return new LWWLattice<>(timestampFn);
	}

	/**
	 * Default instance for map values with a {@code :timestamp} keyword entry.
	 */
	public static final LWWLattice<ACell> INSTANCE = new LWWLattice<>(LWWLattice::extractMapTimestamp);

	@Override
	public V merge(V own, V other) {
		if (own == null) return other;
		if (other == null) return own;

		long ownTS = timestampFn.applyAsLong(own);
		long otherTS = timestampFn.applyAsLong(other);

		if (otherTS > ownTS) return other;
		if (ownTS > otherTS) return own;

		// Equal timestamps — tiebreak deterministically for commutativity
		if (own.equals(other)) return own; // idempotent
		int cmp = own.getHash().compareTo(other.getHash());
		return (cmp >= 0) ? own : other;
	}

	@SuppressWarnings("unchecked")
	private static long extractMapTimestamp(ACell value) {
		if (value instanceof AHashMap<?,?>) {
			ACell ts = ((AHashMap<Keyword, ACell>) value).get(KEY_TIMESTAMP);
			if (ts instanceof CVMLong l) return l.longValue();
		}
		return 0;
	}

	@Override
	public V zero() { return null; }

	@Override
	public boolean checkForeign(V value) { return true; }
}
