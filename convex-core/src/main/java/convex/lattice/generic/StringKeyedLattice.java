package convex.lattice.generic;

import java.util.ArrayList;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.util.Utils;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;

/**
 * Lattice implementation with string-keyed child lattices.
 *
 * <p>Like {@link KeyedLattice} but uses {@link AString} keys and
 * {@link AHashMap} as the value type, making it natively JSON-compatible.
 * Each named key maps to a specific child lattice for per-key merge routing.</p>
 *
 * <p>Use this when the lattice structure needs to be JSON-serialisable
 * throughout (e.g. per-user namespaces in a federated system).</p>
 */
public class StringKeyedLattice extends ALattice<AHashMap<AString, ACell>> {

	private final ArrayList<ALattice<?>> lattices;
	private final ArrayList<AString> keys;

	private StringKeyedLattice(ArrayList<ALattice<?>> lattices, ArrayList<AString> keys) {
		this.lattices = lattices;
		this.keys = keys;
	}

	/**
	 * Creates a StringKeyedLattice from alternating key/lattice pairs.
	 *
	 * <p>Keys can be AString instances or plain Java Strings (which are
	 * automatically interned).</p>
	 *
	 * @param keysAndValues Alternating key (String or AString) and ALattice pairs
	 * @return New StringKeyedLattice
	 */
	public static StringKeyedLattice create(Object... keysAndValues) {
		int n2 = keysAndValues.length;
		int n = n2 / 2;

		if (n * 2 != n2) throw new IllegalArgumentException("Must have pairs of keys and values");

		ArrayList<ALattice<?>> lattices = new ArrayList<>(n);
		ArrayList<AString> keys = new ArrayList<>(n);

		for (int i = 0; i < n; i++) {
			Object keyObj = keysAndValues[2 * i];
			AString k;
			if (keyObj instanceof AString as) {
				k = as;
			} else if (keyObj instanceof String s) {
				k = Strings.intern(s);
			} else {
				throw new IllegalArgumentException("Keys must be String or AString, got: " + keyObj.getClass());
			}

			ALattice<?> v = (ALattice<?>) (keysAndValues[2 * i + 1]);
			if (v == null) throw new NullPointerException("null lattice");

			lattices.add(v);
			keys.add(k);
		}

		return new StringKeyedLattice(lattices, keys);
	}

	/**
	 * Returns a new StringKeyedLattice with an additional key/lattice pair.
	 *
	 * @param key AString key for the new section
	 * @param lattice Lattice for the new section's values
	 * @return New StringKeyedLattice with the additional entry
	 */
	public StringKeyedLattice addLattice(AString key, ALattice<?> lattice) {
		ArrayList<ALattice<?>> newLattices = new ArrayList<>(this.lattices);
		ArrayList<AString> newKeys = new ArrayList<>(this.keys);
		newLattices.add(lattice);
		newKeys.add(key);
		return new StringKeyedLattice(newLattices, newKeys);
	}

	@Override
	public AHashMap<AString, ACell> merge(AHashMap<AString, ACell> ownValue, AHashMap<AString, ACell> otherValue) {
		if (ownValue == null) {
			if (checkForeign(otherValue)) return otherValue;
			return null;
		}
		if (otherValue == null) return ownValue;

		AHashMap<AString, ACell> result = ownValue;

		int n = lattices.size();
		for (int i = 0; i < n; i++) {
			@SuppressWarnings("unchecked")
			ALattice<ACell> lattice = (ALattice<ACell>) lattices.get(i);
			AString key = keys.get(i);

			if (!otherValue.containsKey(key)) continue;

			ACell a = ownValue.get(key);
			ACell b = otherValue.get(key);

			ACell m = lattice.merge(a, b);

			if (!Utils.equals(m, a)) {
				result = result.assoc(key, m);
			}
		}

		return result;
	}

	@Override
	public AHashMap<AString, ACell> merge(LatticeContext context, AHashMap<AString, ACell> ownValue, AHashMap<AString, ACell> otherValue) {
		if (ownValue == null) {
			if (checkForeign(otherValue)) return otherValue;
			return null;
		}
		if (otherValue == null) return ownValue;

		AHashMap<AString, ACell> result = ownValue;

		int n = lattices.size();
		for (int i = 0; i < n; i++) {
			@SuppressWarnings("unchecked")
			ALattice<ACell> lattice = (ALattice<ACell>) lattices.get(i);
			AString key = keys.get(i);

			if (!otherValue.containsKey(key)) continue;

			ACell a = ownValue.get(key);
			ACell b = otherValue.get(key);

			ACell m = lattice.merge(context, a, b);

			if (!Utils.equals(m, a)) {
				result = result.assoc(key, m);
			}
		}

		return result;
	}

	@Override
	public AHashMap<AString, ACell> zero() {
		return Maps.empty();
	}

	@Override
	public boolean checkForeign(AHashMap<AString, ACell> value) {
		return (value instanceof AHashMap);
	}

	@Override
	public ACell resolveKey(ACell key) {
		if (key instanceof AString s) {
			for (int i = 0; i < keys.size(); i++) {
				if (keys.get(i).equals(s)) {
					return keys.get(i);
				}
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell child) {
		if (child instanceof AString s) {
			for (int i = 0; i < keys.size(); i++) {
				if (keys.get(i).equals(s)) {
					return (ALattice<T>) lattices.get(i);
				}
			}
		}
		return null;
	}
}
