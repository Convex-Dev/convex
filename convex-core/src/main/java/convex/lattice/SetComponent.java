package convex.lattice;

import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.lattice.cursor.AForkableCursor;

/**
 * Atomic set operations over a cursor. Nil reads as empty; reads and no-op edits
 * leave absent storage absent. Non-set storage is rejected without changing it.
 *
 * <p>Compatibility checks require only set shape. Applications may override
 * {@link #checkValue} for domain constraints; operations do not call it.
 * Use {@code SetComponent<ACell>} when foreign members may have unfamiliar types.</p>
 *
 * <p>No replication rule is supplied. Ordinary views share their cursor and
 * have a read-only sync. A subclass can own private working state by calling
 * {@code super(forkOf(destination))} and implementing {@link #mergePublication}.
 * Its inherited sync publishes at that destination only.</p>
 *
 * @param <V> Set element type
 */
public class SetComponent<V extends ACell> extends ALatticeComponent<ASet<V>> {

	private static final AString EXPECTED_SET=Strings.intern("Expected a set");

	/** Shares working storage without taking ownership of its publication. */
	public SetComponent(AForkableCursor<ASet<V>> cursor) {
		super(cursor);
	}

	/**
	 * Creates private working state for a specialised set component. The subclass
	 * supplies its publication merge; ordinary set views retain their no-op sync.
	 */
	protected SetComponent(ForkTarget<ASet<V>> target) {
		super(target);
	}

	/** Nil is the absent/empty set; members have no runtime type constraint. */
	@Override public AString checkValue(ACell value) {
		return value==null || value instanceof ASet<?>?null:EXPECTED_SET;
	}

	// Inspect foreign storage as cells before the Java generic cast to ASet.
	@SuppressWarnings("unchecked")
	private AForkableCursor<ACell> values() {
		return (AForkableCursor<ACell>)(AForkableCursor<?>)cursor();
	}

	private ASet<V> requireSet(ACell value) {
		ASet<V> set=RT.ensureSet(value);
		if (set==null) throw new IllegalStateException("Set operation requires a set at this path");
		return set;
	}

	/** Returns the current set (nil means empty), or fails if this path is not a set. */
	public ASet<V> get() {
		return requireSet(values().get());
	}

	public boolean contains(V value) { return get().contains(value); }
	public long size() { return get().count(); }
	public boolean isEmpty() { return get().isEmpty(); }

	/** Adds an element, returning whether it was absent. */
	public boolean add(V value) {
		ACell previous=values().getAndUpdate(set -> requireSet(set).include(value));
		return !requireSet(previous).contains(value);
	}

	/** Removes an element, returning whether it was present. */
	public boolean remove(V value) {
		ACell previous=values().getAndUpdate(set -> set==null?null:requireSet(set).exclude(value));
		return requireSet(previous).contains(value);
	}

	/** Empties an existing set without creating an absent one. */
	public void clear() {
		values().update(set -> set==null || requireSet(set).isEmpty()?set:Sets.empty());
	}
}
