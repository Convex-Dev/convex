package convex.lattice.generic;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Keyword;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALattice;

/**
 * Last-Write-Wins register lattice.
 *
 * Expects values to be {@code AHashMap<Keyword, ACell>} containing a
 * {@code :timestamp} key with a {@link CVMLong} value. Merge picks the
 * value with the higher timestamp.
 *
 * <p>If timestamps are equal but values differ, a deterministic tiebreaker
 * (encoding hash comparison) ensures commutativity.
 *
 * <h2>Lattice Laws</h2>
 * <ul>
 *   <li>Commutative: {@code merge(a, b) = merge(b, a)}</li>
 *   <li>Associative: {@code merge(merge(a, b), c) = merge(a, merge(b, c))}</li>
 *   <li>Idempotent: {@code merge(a, a) = a}</li>
 * </ul>
 */
public class LWWLattice extends ALattice<ACell> {

	public static final Keyword KEY_TIMESTAMP = Keyword.intern("timestamp");

	public static final LWWLattice INSTANCE = new LWWLattice();

	@Override
	public ACell merge(ACell own, ACell other) {
		if (own == null) return other;
		if (other == null) return own;

		long ownTS = getTimestamp(own);
		long otherTS = getTimestamp(other);

		if (otherTS > ownTS) return other;
		if (ownTS > otherTS) return own;

		// Equal timestamps — tiebreak deterministically for commutativity
		if (own.equals(other)) return own; // idempotent
		int cmp = own.getHash().compareTo(other.getHash());
		return (cmp >= 0) ? own : other;
	}

	@SuppressWarnings("unchecked")
	private static long getTimestamp(ACell value) {
		if (value instanceof AHashMap<?,?>) {
			ACell ts = ((AHashMap<Keyword, ACell>) value).get(KEY_TIMESTAMP);
			if (ts instanceof CVMLong l) return l.longValue();
		}
		return 0;
	}

	@Override
	public ACell zero() { return null; }

	@Override
	public boolean checkForeign(ACell value) { return true; }

	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) { return null; }
}
