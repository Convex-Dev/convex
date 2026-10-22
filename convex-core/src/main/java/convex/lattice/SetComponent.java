package convex.lattice;

import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.lattice.cursor.AForkableCursor;

/**
 * Ordinary set operations over shared working storage. An absent set reads as
 * empty; construction, reads and edits which change nothing leave storage alone.
 * Updates are atomic through the supplied cursor, including any enclosing signing
 * or timestamp boundary. Sync does not publish the containing component.
 *
 * <p>This component supplies collection operations, not a replication rule.
 * The enclosing component determines how incoming state merges: for example, a
 * signed last-write-wins record replaces its entire set, preserving removals.
 * A grow-only set may instead use {@code SetLattice} at its merge boundary.</p>
 *
 * <p>Operations require only a set at this path, not a schema for its members.
 * A non-set value is left intact and reported as an unsupported operation on the
 * current state. Use {@code SetComponent<ACell>} when foreign members may have
 * unfamiliar types; ordinary include/exclude operations preserve them.</p>
 *
 * @param <V> Set element type
 */
public class SetComponent<V extends ACell> extends ALatticeComponent<ASet<V>> {

	private final Class<V> elementType;

	/** Checks set shape only; Java generics do not supply a runtime element constraint. */
	public SetComponent(AForkableCursor<ASet<V>> cursor) {
		this(cursor,null);
	}

	/** Supplies an optional runtime element type for explicit compatibility checks. */
	public SetComponent(AForkableCursor<ASet<V>> cursor,Class<V> elementType) {
		super(cursor);
		this.elementType=elementType;
	}

	/** Nil is the absent/empty set. Typed sets require every element to match. */
	@Override public AString checkValue(ACell value) {
		if (value==null) return null;
		if (!(value instanceof ASet<?> set)) return Strings.create("Expected a set");
		for (ACell element:set) {
			AString error=checkElement(element);
			if (error!=null) return error;
		}
		return null;
	}

	/** Override for domain element constraints; called only by explicit checks. */
	protected AString checkElement(ACell value) {
		return elementType==null || elementType.isInstance(value)?null:
			Strings.create("Expected set element of type "+elementType.getSimpleName());
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
