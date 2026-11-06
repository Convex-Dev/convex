package convex.lattice.cursor;

import convex.core.data.ACell;
import convex.core.data.ADataStructure;
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
			if (!(value instanceof ADataStructure<?> data)) return ACursor.MISSING;
			ACell key=path[i];
			if (data instanceof ASequence<?> sequence) {
				if (!AInteger.isLong(key)) return ACursor.MISSING;
				long index=((AInteger)key).longValue();
				if (index<0 || index>=sequence.count()) return ACursor.MISSING;
				value=sequence.get(index);
			} else {
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
