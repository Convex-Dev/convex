package convex.lattice;

import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.lattice.cursor.AForkableCursor;

/**
 * Atomic set operations over a cursor. Missing storage reads as null; reads and
 * no-op edits leave it absent. Non-set storage is rejected without changing it.
 * Subclasses can override {@link #createContainer()} to control initialisation.
 *
 * <p>Compatibility checks require only set shape. Applications may override
 * {@link #checkValue} for domain constraints; operations do not call it.
 * Use {@code SetComponent<ACell>} when foreign members may have unfamiliar types.</p>
 *
 * <p>No replication rule is supplied. Ordinary views share their cursor and
 * have a read-only sync. A subclass can implement {@link #rebind} and
 * {@link #mergePublication} to support independent working copies. Sync publishes
 * a bound fork at its destination only.</p>
 *
 * @param <V> Set element type
 */
public class SetComponent<V extends ACell> extends ALatticeComponent<ASet<V>> {

	private static final AString EXPECTED_SET=Strings.intern("Expected a set");

	/** Binds supplied working storage. Fork publication requires a domain merge rule. */
	public SetComponent(AForkableCursor<ASet<V>> cursor) {
		super(cursor);
	}

	/**
	 * Creates a set for an add to absent storage. Override to provide initial members
	 * or throw to forbid creation. Return a non-null set. This hook must be pure:
	 * atomic updates may retry it. Reads, removals and clear never call it.
	 */
	protected ASet<V> createContainer() { return Sets.empty(); }

	/** Nil denotes absence; members have no runtime type constraint. */
	@Override public AString checkValue(ACell value) {
		return value==null || value instanceof ASet<?>?null:EXPECTED_SET;
	}

	// Inspect foreign storage as cells before the Java generic cast to ASet.
	@SuppressWarnings("unchecked")
	private AForkableCursor<ACell> values() {
		return (AForkableCursor<ACell>)(AForkableCursor<?>)cursor();
	}

	private ASet<V> requireSet(ACell value) {
		if (value==null) return null;
		ASet<V> set=RT.ensureSet(value);
		if (set==null) throw new IllegalStateException("Set operation requires a set at this path");
		return set;
	}

	/** Returns the current set or null for absence; fails on incompatible storage. */
	public ASet<V> get() {
		return requireSet(values().get());
	}

	public boolean contains(V value) { ASet<V> set=get(); return set!=null && set.contains(value); }
	public long size() { ASet<V> set=get(); return set==null?0:set.count(); }
	public boolean isEmpty() { return size()==0; }

	/** Adds an element, returning whether it was absent. */
	public boolean add(V value) {
		ACell previous=values().getAndUpdate(stored -> {
			ASet<V> set=requireSet(stored);
			return (set==null?createContainer():set).include(value);
		});
		return previous==null || !requireSet(previous).contains(value);
	}

	/** Removes an element, returning whether it was present. */
	public boolean remove(V value) {
		ACell previous=values().getAndUpdate(set -> set==null?null:requireSet(set).exclude(value));
		return previous!=null && requireSet(previous).contains(value);
	}

	/** Empties an existing set without creating an absent one. */
	public void clear() {
		values().update(set -> set==null || requireSet(set).isEmpty()?set:Sets.empty());
	}
}
