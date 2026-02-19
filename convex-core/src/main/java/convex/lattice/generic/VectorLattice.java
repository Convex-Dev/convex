package convex.lattice.generic;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.util.MergeFunction;
import convex.lattice.ALattice;

/**
 * Variable-size lattice over AVector&lt;V&gt; with a uniform child lattice.
 *
 * <p>Uses tree-wise merge via {@link AVector#mergeWith} to avoid full
 * element-wise traversal when subtrees are identical.</p>
 *
 * @param <V> Element type
 */
public class VectorLattice<V extends ACell> extends ALattice<AVector<V>> {

	private final ALattice<V> elementLattice;
	private final MergeFunction<V> mergeFunction;

	private VectorLattice(ALattice<V> elementLattice) {
		this.elementLattice = elementLattice;
		this.mergeFunction = (a, b) -> elementLattice.merge(a, b);
	}

	public static <V extends ACell> VectorLattice<V> create(ALattice<V> elementLattice) {
		return new VectorLattice<>(elementLattice);
	}

	@Override
	public AVector<V> merge(AVector<V> own, AVector<V> other) {
		if (own == null) return other;
		if (other == null) return own;
		return own.mergeWith(other, mergeFunction);
	}

	@Override
	public AVector<V> zero() {
		return Vectors.empty();
	}

	@Override
	public boolean checkForeign(AVector<V> value) {
		return value instanceof AVector;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		if (childKey instanceof CVMLong) {
			return (ALattice<T>) elementLattice;
		}
		return null;
	}
}
