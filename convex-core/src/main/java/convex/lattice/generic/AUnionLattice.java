package convex.lattice.generic;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.util.MergeFunction;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;

/**
 * Base for collections that merge as a union of keys, each entry merged by the child
 * lattice {@link #path} returns for its key.
 *
 * <p>This is one way a map can merge, not the only one: a map may equally be the leaf
 * of a last-writer-wins rule with navigation continuing below it. A lattice extends
 * this base only when its entries merge independently, and then it supplies the
 * child rule alone. The child may depend on the key, as an owner's slot is bound to
 * its owner and a content-addressed entry to its hash, so navigation and merge
 * cannot disagree about which rule applies at a key. A key for which {@code path}
 * returns null is outside the lattice: it is dropped from an incoming value and left
 * as it is where already held. Entries present only on the own side are not
 * revisited; they passed the child rule when they were merged or written.</p>
 *
 * <p>The merge visits only the keys on which the two maps differ, so its cost is
 * proportional to the difference. A value that is not this lattice's container type
 * aborts the merge, which leaves the prior value in place.</p>
 *
 * @param <K> Key type
 * @param <V> Entry value type
 * @param <M> Concrete map type
 */
public abstract class AUnionLattice<K extends ACell, V extends ACell, M extends AMap<K,V>> extends ALattice<M> {

	/** Checks a changed foreign entry's representation before invoking a typed child. */
	protected boolean checkEntry(ACell key,ACell value) { return true; }

	@Override
	public M merge(M own, M other) {
		return mergeEntries(null,own,other);
	}

	@Override
	public M merge(LatticeContext context, M own, M other) {
		return mergeEntries(context,own,other);
	}

	@SuppressWarnings("unchecked")
	private M mergeEntries(LatticeContext context, M own, M other) {
		if (other==null) return own;
		if (!checkForeign(other)) throw new IllegalArgumentException("Invalid value for "+getClass().getSimpleName());
		// #561: merge against an empty own rather than adopting the map wholesale, so every
		// foreign entry is validated by its child rule
		if (own==null) own=zero();
		MergeFunction<V> merger=new MergeFunction<>() {
			@Override
			public V merge(V a, V b) {
				throw new IllegalStateException("Union merge requires the entry key");
			}

			@Override
			public V merge(Object key, V a, V b) {
				if (b==null) return a;
				if (key!=null && !(key instanceof ACell)) return a;
				ACell cell=(ACell)key; // nil is a valid hash-map key
				if (!checkEntry(cell,b)) return a;
				ALattice<V> child=path(cell);
				if (child==null) return a;
				return (context==null)?child.merge(a,b):child.merge(context,a,b);
			}
		};
		return (M)own.mergeDifferences(other,merger);
	}
}
