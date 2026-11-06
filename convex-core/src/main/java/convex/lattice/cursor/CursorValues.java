package convex.lattice.cursor;

import convex.core.data.ACell;
import convex.core.data.ABlobLike;
import convex.core.data.ADataStructure;
import convex.core.data.AIndex;
import convex.core.data.ASequence;
import convex.core.data.ASet;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMBool;

/** Presence-aware reads of one immutable snapshot, without allocating result objects. */
final class CursorValues {
	private CursorValues() {}

	@SuppressWarnings("unchecked")
	static <V extends ACell> V require(Object value) {
		if (value==ACursor.MISSING) throw new MissingValueException();
		return (V)value;
	}

	static Object at(Object value,ACell[] path) {
		for (int i=0; i<path.length; i++) {
			if (value==ACursor.MISSING || value==null) return ACursor.MISSING;
			if (!(value instanceof ADataStructure<?> data)) {
				throw new IllegalArgumentException("Cannot navigate through a non-collection at path depth "+i);
			}
			ACell key=path[i];
			if (data instanceof ASequence<?> sequence) {
				if (!(key instanceof AInteger)) throw new IllegalArgumentException("Sequence path key must be an integer index at depth "+i);
				if (!AInteger.isLong(key)) throw new IndexOutOfBoundsException("Sequence index exceeds long range at path depth "+i);
				long index=((AInteger)key).longValue();
				if (index<0 || index>=sequence.count()) {
					throw new IndexOutOfBoundsException("Sequence index "+index+" at path depth "+i+" (size "+sequence.count()+")");
				}
				value=sequence.get(index);
			} else {
				if (data instanceof AIndex<?,?> && !(key instanceof ABlobLike<?>)) {
					throw new IllegalArgumentException("Index path key must be blob-like at depth "+i);
				}
				ACell child=data.get(key);
				if (data instanceof ASet<?>) {
					if (CVMBool.FALSE.equals(child)) return ACursor.MISSING;
				} else if (child==null && !data.containsKey(key)) {
					return ACursor.MISSING;
				}
				value=child;
			}
		}
		return value;
	}
}
