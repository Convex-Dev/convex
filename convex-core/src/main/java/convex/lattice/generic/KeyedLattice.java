package convex.lattice.generic;

import java.util.ArrayList;

import convex.core.data.ABlob;
import convex.core.data.ABlobLike;
import convex.core.data.ACell;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.lattice.ALattice;

/**
 * Lattice with a fixed set of {@link Keyword}-keyed child lattices, backed by an
 * {@link Index}.
 *
 * <p>Uses {@link Index} as the value type, which provides blob-based key
 * comparison. This means both {@link Keyword} and {@code AString} keys with the
 * same text resolve to the same entry, enabling JSON path compatibility (JSON
 * strings map to the same blob as keywords).</p>
 *
 * <p>Used for data structures using known sets of interned Keywords as keys
 * requiring different child lattices. The per-key merge, navigation and key
 * resolution live in {@link AKeyedLattice}.</p>
 */
public class KeyedLattice extends AKeyedLattice<Keyword, Index<Keyword, ACell>> {

	/** Blob forms of the keys, for blob-based (Keyword/AString-compatible) matching. */
	private final ArrayList<ABlob> keyBlobs;

	private KeyedLattice(ArrayList<ALattice<?>> lattices, ArrayList<Keyword> keys) {
		super(lattices, keys);
		this.keyBlobs = new ArrayList<>(keys.size());
		for (Keyword k : keys) {
			keyBlobs.add(k.toBlob());
		}
	}

	public static KeyedLattice create(Object... keysAndValues) {
		int n2 = keysAndValues.length;
		int n = n2 / 2;

		if (n * 2 != n2) throw new IllegalArgumentException("Must have pairs of keys and values");

		ArrayList<ALattice<?>> lattices = new ArrayList<>(n);
		ArrayList<Keyword> keys = new ArrayList<>(n);

		for (int i = 0; i < n; i++) {
			Keyword k = Keyword.create(keysAndValues[2 * i]);
			if (k == null) throw new IllegalArgumentException("Invalid key name");

			ALattice<?> v = (ALattice<?>) (keysAndValues[2 * i + 1]);
			if (v == null) throw new NullPointerException("null lattice");

			lattices.add(v);
			keys.add(k);
		}

		return new KeyedLattice(lattices, keys);
	}

	/**
	 * Returns a new KeyedLattice with an additional key/lattice pair.
	 *
	 * @param key Keyword for the new section
	 * @param lattice Lattice for the new section's values
	 * @return New KeyedLattice with the additional entry
	 */
	@Override
	public KeyedLattice addLattice(Keyword key, ALattice<?> lattice) {
		return (KeyedLattice) super.addLattice(key, lattice);
	}

	@Override
	protected AKeyedLattice<Keyword, Index<Keyword, ACell>> construct(ArrayList<ALattice<?>> lattices, ArrayList<Keyword> keys) {
		return new KeyedLattice(lattices, keys);
	}

	@SuppressWarnings("unchecked")
	@Override
	protected Index<Keyword, ACell> emptyMap() {
		return (Index<Keyword, ACell>) Index.EMPTY;
	}

	@Override
	public boolean checkForeign(Index<Keyword, ACell> value) {
		return (value instanceof Index);
	}

	/**
	 * Matches by blob equality, so a {@link Keyword} and an {@code AString} with the
	 * same text ({@code :data} and {@code "data"}) resolve to the same slot.
	 */
	@Override
	protected int indexOfKey(ACell externalKey) {
		if (externalKey instanceof ABlobLike<?> blobLike) {
			ABlob childBlob = blobLike.toBlob();
			for (int i = 0; i < keyBlobs.size(); i++) {
				if (keyBlobs.get(i).equals(childBlob)) return i;
			}
		}
		return -1;
	}
}
