package convex.lattice.generic;

import java.util.ArrayList;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.util.Utils;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;

/**
 * Abstract base for lattices with a fixed set of named keys, each routing to its
 * own child lattice for per-key merge. Concrete subclasses fix the key type and
 * the backing map container:
 *
 * <ul>
 *   <li>{@link KeyedLattice} — {@link convex.core.data.Keyword} keys over an
 *       {@link convex.core.data.Index} (blob-ordered; Keyword/AString-compatible)</li>
 *   <li>{@link StringKeyedLattice} — {@link convex.core.data.AString} keys over an
 *       {@link convex.core.data.AHashMap} (natively JSON-compatible)</li>
 * </ul>
 *
 * <p>The per-key merge loop (context-threaded), {@code zero}, {@code resolveKey},
 * {@code path} and {@code addLattice} live here. Subclasses supply only the empty
 * container, the foreign-type check, the external-key lookup ({@link #indexOfKey}),
 * and how to reconstruct themselves ({@link #construct}).</p>
 *
 * @param <K> Canonical key type
 * @param <M> Backing map type (an {@link AMap} of {@code K → ACell})
 */
public abstract class AKeyedLattice<K extends ACell, M extends AMap<K, ACell>> extends ALattice<M> {

	protected final ArrayList<ALattice<?>> lattices;
	protected final ArrayList<K> keys;

	protected AKeyedLattice(ArrayList<ALattice<?>> lattices, ArrayList<K> keys) {
		this.lattices = lattices;
		this.keys = keys;
	}

	/** Empty backing container, used as {@link #zero()}. */
	protected abstract M emptyMap();

	/**
	 * Reconstructs a lattice of this concrete type with the given entries.
	 * Used by {@link #addLattice}.
	 */
	protected abstract AKeyedLattice<K, M> construct(ArrayList<ALattice<?>> lattices, ArrayList<K> keys);

	/**
	 * Returns the index of the registered slot matching an external key, or -1 if
	 * none. Subclasses implement the key-matching rule (e.g. blob equality for
	 * keywords, string equality for strings).
	 *
	 * @param externalKey External key to look up
	 * @return Slot index, or -1 if no registered key matches
	 */
	protected abstract int indexOfKey(ACell externalKey);

	/**
	 * Returns a new lattice with an additional key/child-lattice pair.
	 *
	 * @param key Canonical key for the new section
	 * @param lattice Child lattice for the new section's values
	 * @return New lattice with the additional entry
	 */
	public AKeyedLattice<K, M> addLattice(K key, ALattice<?> lattice) {
		ArrayList<ALattice<?>> newLattices = new ArrayList<>(this.lattices);
		ArrayList<K> newKeys = new ArrayList<>(this.keys);
		newLattices.add(lattice);
		newKeys.add(key);
		return construct(newLattices, newKeys);
	}

	@Override
	public M merge(M ownValue, M otherValue) {
		return mergeImpl(null, ownValue, otherValue);
	}

	@Override
	public M merge(LatticeContext context, M ownValue, M otherValue) {
		return mergeImpl(context, ownValue, otherValue);
	}

	@SuppressWarnings("unchecked")
	private M mergeImpl(LatticeContext context, M ownValue, M otherValue) {
		if (otherValue == null) return ownValue;
		// #561: never wholesale-accept a foreign map on the first (own==null) merge into an
		// unpopulated region. Start from an empty own so every registered key's foreign value
		// is validated through its child merge below, and unregistered keys are dropped.
		if (ownValue == null) {
			ownValue = zero();
		}

		M result = ownValue;
		int n = lattices.size();
		for (int i = 0; i < n; i++) {
			ALattice<ACell> lattice = (ALattice<ACell>) lattices.get(i);
			K key = keys.get(i);

			if (!otherValue.containsKey(key)) continue;

			ACell a = ownValue.get(key);
			ACell b = otherValue.get(key);

			// Thread context to the child merge (null context → plain merge) so a
			// signed / owner-verified child is not silently merged without its context.
			ACell m = (context == null) ? lattice.merge(a, b) : lattice.merge(context, a, b);

			if (!Utils.equals(m, a)) {
				result = (M) result.assoc(key, m);
			}
		}
		return result;
	}

	@Override
	public M zero() {
		return emptyMap();
	}

	@Override
	public ACell resolveKey(ACell key) {
		int i = indexOfKey(key);
		return (i >= 0) ? keys.get(i) : null;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell child) {
		int i = indexOfKey(child);
		return (i >= 0) ? (ALattice<T>) lattices.get(i) : null;
	}
}
