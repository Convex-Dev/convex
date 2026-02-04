package convex.lattice.generic;

import java.util.ArrayList;

import convex.core.data.ABlobLike;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.util.Utils;
import convex.lattice.ALattice;

/**
 * Lattice implementation that handles a set of keyword-mapped child lattices.
 *
 * <p>Uses {@link Index} as the value type, which provides blob-based key
 * comparison. This means both {@link Keyword} and {@code AString} keys
 * with the same text resolve to the same entry, enabling JSON path
 * compatibility (JSON strings map to the same blob as keywords).
 */
public class KeyedLattice extends ALattice<Index<Keyword, ACell>> {

	private final ArrayList<ALattice<?>> lattices;
	private final ArrayList<Keyword> keys;
	private final ArrayList<ABlob> keyBlobs;

	private KeyedLattice(ArrayList<ALattice<?>> lattices, ArrayList<Keyword> keys) {
		this.lattices=lattices;
		this.keys=keys;
		this.keyBlobs=new ArrayList<>(keys.size());
		for (Keyword k : keys) {
			keyBlobs.add(k.toBlob());
		}
	}

	public static KeyedLattice create(Object... keysAndValues) {
		int n2=keysAndValues.length;
		int n=n2/2;

		if (n*2!=n2) throw new IllegalArgumentException("Must have pairs of keys and values");

		ArrayList<ALattice<?>> lattices=new ArrayList<>(n);
		ArrayList<Keyword> keys =new ArrayList<>(n);

		for (int i=0; i<n; i++) {
			Keyword k=Keyword.create(keysAndValues[2*i]);
			if (k==null) throw new IllegalArgumentException("Invalid key name");

			ALattice<?> v=(ALattice<?>) (keysAndValues[2*i+1]);
			if (v==null) throw new NullPointerException("null lattice");

			lattices.add(v);
			keys.add(k);
		}

		return new KeyedLattice(lattices,keys);
	}

	@Override
	public Index<Keyword, ACell> merge(Index<Keyword, ACell> ownValue, Index<Keyword, ACell> otherValue) {
		if (ownValue==null) {
			if (checkForeign(otherValue)) return otherValue;
			return null;
		}
		if (otherValue==null) return ownValue;

		Index<Keyword, ACell> result=ownValue;

		int n=lattices.size();
		for (int i=0; i<n; i++) {
			@SuppressWarnings("unchecked")
			ALattice<ACell> lattice=(ALattice<ACell>) lattices.get(i);
			Keyword key=keys.get(i);

			if (!otherValue.containsKey(key)) continue;

			ACell a=ownValue.get(key);
			ACell b=otherValue.get(key);

			ACell m=lattice.merge(a, b); // child merge

			if (!Utils.equals(m, a)) {
				result=result.assoc(key, m);
			}
		}

		return result;
	}

	@SuppressWarnings("unchecked")
	@Override
	public Index<Keyword, ACell> zero() {
		return (Index<Keyword, ACell>) Index.EMPTY;
	}

	@Override
	public boolean checkForeign(Index<Keyword, ACell> value) {
		return (value instanceof Index);
	}

	/**
	 * Resolves a child lattice by key. Uses blob-based comparison so that
	 * both Keywords and AStrings with the same text resolve to the same
	 * child lattice (e.g. {@code :data} and {@code "data"} are equivalent).
	 */
	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell child) {
		if (child instanceof ABlobLike<?> blobLike) {
			ABlob childBlob=blobLike.toBlob();
			for (int i=0; i<keyBlobs.size(); i++) {
				if (keyBlobs.get(i).equals(childBlob)) {
					return (ALattice<T>) lattices.get(i);
				}
			}
		}
		return null;
	}
}
