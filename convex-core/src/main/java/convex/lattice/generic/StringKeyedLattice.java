package convex.lattice.generic;

import java.util.ArrayList;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.lattice.ALattice;

/**
 * Lattice with a fixed set of {@link AString}-keyed child lattices, backed by an
 * {@link AHashMap}.
 *
 * <p>Like {@link KeyedLattice} but uses {@link AString} keys and {@link AHashMap}
 * as the value type, making it natively JSON-compatible throughout. Each named key
 * maps to a specific child lattice for per-key merge routing.</p>
 *
 * <p>Use this when the lattice structure needs to be JSON-serialisable throughout
 * (e.g. per-user namespaces in a federated system). The per-key merge, navigation
 * and key resolution live in {@link AKeyedLattice}.</p>
 */
public class StringKeyedLattice extends AKeyedLattice<AString, AHashMap<AString, ACell>> {

	private StringKeyedLattice(ArrayList<ALattice<?>> lattices, ArrayList<AString> keys) {
		super(lattices, keys);
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
	@Override
	public StringKeyedLattice addLattice(AString key, ALattice<?> lattice) {
		return (StringKeyedLattice) super.addLattice(key, lattice);
	}

	@Override
	protected AKeyedLattice<AString, AHashMap<AString, ACell>> construct(ArrayList<ALattice<?>> lattices, ArrayList<AString> keys) {
		return new StringKeyedLattice(lattices, keys);
	}

	@Override
	protected AHashMap<AString, ACell> emptyMap() {
		return Maps.empty();
	}

	@Override
	public boolean checkForeign(AHashMap<AString, ACell> value) {
		return (value instanceof AHashMap);
	}

	@Override
	protected int indexOfKey(ACell externalKey) {
		if (externalKey instanceof AString s) {
			for (int i = 0; i < keys.size(); i++) {
				if (keys.get(i).equals(s)) return i;
			}
		}
		return -1;
	}
}
