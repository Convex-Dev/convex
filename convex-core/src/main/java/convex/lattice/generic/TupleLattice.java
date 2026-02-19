package convex.lattice.generic;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALattice;

/**
 * Fixed-size product lattice over AVector&lt;ACell&gt;.
 *
 * <p>Each position in the vector has its own child lattice. Merge is
 * element-wise, delegating to the child lattice at each index.</p>
 */
public class TupleLattice extends ALattice<AVector<ACell>> {

	private final ALattice<ACell>[] children;

	@SuppressWarnings("unchecked")
	private TupleLattice(ALattice<?>[] children) {
		this.children = new ALattice[children.length];
		System.arraycopy(children, 0, this.children, 0, children.length);
	}

	public static TupleLattice create(ALattice<?>... children) {
		return new TupleLattice(children);
	}

	@Override
	public AVector<ACell> merge(AVector<ACell> own, AVector<ACell> other) {
		if (own == null) return other;
		if (other == null) return own;

		int n = children.length;
		AVector<ACell> result = own;
		boolean sameAsOwn = true;
		boolean sameAsOther = true;

		for (int i = 0; i < n; i++) {
			ACell ov = own.get(i);
			ACell tv = other.get(i);
			ACell mv = children[i].merge(ov, tv);
			if (mv != ov) {
				result = result.assoc(i, mv);
				sameAsOwn = false;
			}
			if (mv != tv) sameAsOther = false;
		}

		if (sameAsOwn) return own;
		if (sameAsOther) return other;
		return result;
	}

	@Override
	public AVector<ACell> zero() {
		int n = children.length;
		ACell[] zeros = new ACell[n];
		for (int i = 0; i < n; i++) {
			zeros[i] = children[i].zero();
		}
		return Vectors.of(zeros);
	}

	@Override
	public boolean checkForeign(AVector<ACell> value) {
		if (value == null) return false;
		return value.count() == children.length;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		if (childKey instanceof CVMLong l) {
			int index = (int) l.longValue();
			if (index >= 0 && index < children.length) {
				return (ALattice<T>) children[index];
			}
		}
		return null;
	}
}
