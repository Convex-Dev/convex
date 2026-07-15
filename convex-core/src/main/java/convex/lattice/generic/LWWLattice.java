package convex.lattice.generic;

import java.util.function.ToLongFunction;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Keyword;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;

/**
 * Last-Write-Wins register lattice — a <b>merge</b> layer.
 *
 * <p>Merge picks the value with the higher timestamp, as extracted by a
 * caller-provided function. On a tie (equal timestamps, different values) the own
 * (local) value is preferred. This reduces risk from malicious or spurious
 * incoming values, retains existing structure for caching, and avoids unnecessary
 * state churn. Merge is whole-value: it <em>never</em> recurses into the inner
 * lattice, so deletions are durable.</p>
 *
 * <p>The equal-timestamp rule is intentionally directional. For distinct values
 * with the same timestamp, {@code merge(a, b) == a} and
 * {@code merge(b, a) == b}. Callers must put the value that should survive an
 * unresolved tie in the {@code own} position. In particular, cursor fork/sync
 * reconciliation treats the local edit as own, while an external merge normally
 * treats the current local value as own and the received value as other.</p>
 *
 * <p>As an {@link ADelegatingLattice} it owns only the merge concern — navigation
 * and write-interception are delegated to an optional inner lattice (terminal
 * register when none). It is the sibling of {@link LWPLattice}, which instead
 * <em>delegates</em> merge to the inner lattice. For stamp-on-write, wrap with a
 * {@link StampingLattice}: {@code StampingLattice.create(LWWLattice.create(inner, ts), stamp)}.</p>
 *
 * @param <V> Type of lattice values
 */
public class LWWLattice<V extends ACell> extends ADelegatingLattice<V> {

	public static final Keyword KEY_TIMESTAMP = Keyword.intern("timestamp");

	private final ToLongFunction<V> timestampFn;

	private LWWLattice(ToLongFunction<V> timestampFn, ALattice<V> inner) {
		super(inner);
		this.timestampFn = timestampFn;
	}

	/**
	 * Creates a terminal LWW register with a custom timestamp extractor.
	 * @param <V> Value type
	 * @param timestampFn Function to extract a long timestamp from a value
	 * @return New LWWLattice instance
	 */
	public static <V extends ACell> LWWLattice<V> create(ToLongFunction<V> timestampFn) {
		return new LWWLattice<>(timestampFn, null);
	}

	/**
	 * Creates an LWW lattice that merges whole-value by timestamp but delegates
	 * navigation to an inner lattice.
	 *
	 * @param <V> Value type
	 * @param inner Lattice to delegate navigation to
	 * @param timestampFn Function to extract a long timestamp from a value
	 * @return New LWWLattice instance
	 */
	public static <V extends ACell> LWWLattice<V> create(ALattice<V> inner, ToLongFunction<V> timestampFn) {
		return new LWWLattice<>(timestampFn, inner);
	}

	/**
	 * Default instance for map values with a {@code :timestamp} keyword entry.
	 */
	public static final LWWLattice<ACell> INSTANCE = new LWWLattice<>(LWWLattice::extractMapTimestamp, null);

	@Override
	public V merge(V own, V other) {
		if (own == null) return other;
		if (other == null) return own;

		long ownTS = timestampFn.applyAsLong(own);
		long otherTS = timestampFn.applyAsLong(other);

		if (otherTS > ownTS) return other;
		// Equal or own is newer — prefer own value
		return own;
	}

	@Override
	public V merge(LatticeContext context, V own, V other) {
		return merge(own, other); // LWW resolves by value timestamp, ignoring context
	}

	@SuppressWarnings("unchecked")
	private static long extractMapTimestamp(ACell value) {
		if (value instanceof AHashMap<?,?>) {
			ACell ts = ((AHashMap<Keyword, ACell>) value).get(KEY_TIMESTAMP);
			if (ts instanceof CVMLong l) return l.longValue();
		}
		return 0;
	}
}
