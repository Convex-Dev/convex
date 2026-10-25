package convex.lattice;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Keyword;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.StampedCursor;
import convex.lattice.generic.LWWLattice;

/**
 * A timestamped record over shared storage. Local edits through {@link #cursor()}
 * stamp the whole record before writing it; incoming merges select the record with
 * the higher {@code :timestamp}, retaining the current record on ties. Descendant
 * components use the editing cursor and need no timestamp logic of their own.
 *
 * <p>This is a live wrapper, with no independent working copy or publication
 * boundary. Sync does not publish the supplied storage. Enclosing signed or staged
 * components use {@link #getLattice()} for the same ordering during verification
 * and publication conflicts. The standard {@link #create} factory uses hash maps
 * with a {@link CVMLong} timestamp and the shared {@link #lattice()} rule. The
 * wrapper never re-stamps incoming records or accepts partial incoming paths.</p>
 *
 * <p>The caller supplies any default container through its storage cursor, for
 * example {@code storage.withDefault(Maps.empty())}. Construction and no-op edits
 * leave absent storage absent. Clearing the whole record to null is a local
 * removal, not a replicated tombstone; publish a stamped empty record to retain
 * deletion under merge.</p>
 *
 * @param <V> Record type
 */
public final class Timestamped<V extends ACell> extends ALatticeComponent<V> {

	public static final Keyword TIMESTAMP=LWWLattice.KEY_TIMESTAMP;

	private static final ALattice<AHashMap<Keyword,ACell>> ORDER=new ALattice<>() {
		private final LWWLattice<AHashMap<Keyword,ACell>> ordering=LWWLattice.create(
			record -> ((CVMLong)record.get(TIMESTAMP)).longValue());

		@Override public AHashMap<Keyword,ACell> merge(AHashMap<Keyword,ACell> own,AHashMap<Keyword,ACell> other) {
			if (!checkForeign(other)) return own;
			// An unstamped default container does not compete with an incoming record.
			return checkForeign(own)?ordering.merge(own,other):other;
		}

		@Override public boolean checkForeign(AHashMap<Keyword,ACell> value) {
			return value!=null && value.get(TIMESTAMP) instanceof CVMLong;
		}

		@Override public AHashMap<Keyword,ACell> zero() { return null; }
		@Override public <T extends ACell> ALattice<T> path(ACell key) { return null; }
	};

	private final AForkableCursor<V> unstamped;
	private final ALattice<V> ordering;

	/** Wraps a custom record representation with its ordering, clock and stamping rule. */
	public Timestamped(AForkableCursor<V> storage,ALattice<V> ordering,
			Supplier<CVMLong> getTimestamp,BiFunction<V,CVMLong,V> stamp) {
		super(StampedCursor.create(Objects.requireNonNull(storage),getTimestamp,stamp));
		this.unstamped=storage;
		this.ordering=Objects.requireNonNull(ordering);
	}

	/** Standard hash-map record with a {@code :timestamp} field and whole-record ordering. */
	public static Timestamped<AHashMap<Keyword,ACell>> create(
			AForkableCursor<AHashMap<Keyword,ACell>> storage,Supplier<CVMLong> getTimestamp) {
		return new Timestamped<>(storage,ORDER,getTimestamp,
			(record,time) -> record.assoc(TIMESTAMP,Objects.requireNonNull(time,"Timestamp must not be null")));
	}

	/** Shared whole-record ordering for this wrapper and its enclosing boundaries. */
	public static ALattice<AHashMap<Keyword,ACell>> lattice() {
		return ORDER;
	}

	/** Uses the supplied lattice's precheck; merge may impose stronger rules. Nil is absent state. */
	@Override public AString checkValue(ACell value) {
		return LatticeOps.checkValue(ordering,value);
	}

	public ALattice<V> getLattice() { return ordering; }

	/** Selects a complete incoming record without consulting the clock or re-stamping. */
	@Override public V merge(V incoming) {
		return unstamped.updateAndGet(own -> getLattice().merge(own,incoming));
	}

	/** Incoming updates supply the complete timestamped record. */
	@Override public V mergeAt(ACell incoming,ACell... path) {
		return path.length==0?super.mergeAt(incoming):unstamped.get();
	}
}
