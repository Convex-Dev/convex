package convex.lattice;

import java.util.Objects;

import convex.core.data.ACell;
import convex.core.data.ADataStructure;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Keyword;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.ATransformCursor;

/**
 * Explicit JSON addressing over a value location. Objects use string keys, or
 * existing keyword keys when the string key is absent. A present string key wins
 * even when its value is nil. Vectors use non-negative ASCII decimal indices;
 * leading zeroes are accepted, signs and whitespace are not. Other segments stay
 * literal, so an unresolvable path reads as absent under the cursor contract.
 *
 * <p>Resolution interprets all keys against one snapshot and binds those keys.
 * Later changes do not reinterpret a resolved key: resolve again to select keys
 * against a new shape. Missing levels do not prescribe future container types.
 * No whole-value JSON validation, merge or container creation is supplied.</p>
 *
 * @param <V> Value type at this location
 */
public class JSONValueComponent<V extends ACell> extends ValueComponent<V> {

	public JSONValueComponent(AForkableCursor<V> cursor) {
		super(cursor);
	}

	@Override
	public JSONValueComponent<?> resolve(AString[] path,int start,int end) {
		Objects.checkFromToIndex(start,end,path.length);
		if (start==end) return this;
		ACell[] keys=new ACell[end-start];
		AForkableCursor<V> source=cursor();
		ACell current=source.getOrDefault(null);
		for (int i=start; i<end; i++) {
			AString segment=Objects.requireNonNull(path[i],"Path segment must not be null");
			ACell key=resolveKey(current,segment);
			keys[i-start]=key;
			current=current instanceof ADataStructure<?> data?data.get(key):null;
		}
		return new JSONValueComponent<>(new JSONPathCursor(source,keys));
	}

	/** Interprets one external segment against its container in the resolution snapshot. */
	private static ACell resolveKey(ACell container,AString segment) {
		if (container instanceof AVector<?>) {
			CVMLong index=arrayIndex(segment);
			return index==null?segment:index;
		}
		if (container instanceof AMap<?,?> map && !map.containsKey(segment)) {
			Keyword keyword=Keyword.create(segment);
			if (keyword!=null && map.containsKey(keyword)) return keyword;
		}
		return segment;
	}

	private static CVMLong arrayIndex(AString segment) {
		long count=segment.count();
		if (count==0) return null;
		long index=0;
		for (long i=0; i<count; i++) {
			int digit=segment.byteAt(i)-'0';
			if (digit<0 || digit>9 || index>(Long.MAX_VALUE-digit)/10) return null;
			index=index*10+digit;
		}
		return CVMLong.create(index);
	}

	/** Owns the keys produced by resolution; they never escape to a caller. */
	private static final class JSONPathCursor extends ATransformCursor<ACell,ACell> {
		private final ACell[] keys;

		@SuppressWarnings("unchecked")
		JSONPathCursor(AForkableCursor<?> source,ACell[] keys) {
			super((AForkableCursor<ACell>)source);
			this.keys=keys;
		}

		@Override protected Object viewValue(ACell stored) {
			ACell current=stored;
			for (ACell key:keys) {
				if (AInteger.isLong(key)) {
					if (!(current instanceof AVector<?> vector)) return MISSING;
					long i=((AInteger)key).longValue();
					if (i<0 || i>=vector.count()) return MISSING;
					current=vector.get(i);
				} else {
					if (!(current instanceof AMap<?,?> map)) return MISSING;
					ACell value=map.get(key);
					if (value==null && !map.containsKey(key)) return MISSING;
					current=value;
				}
			}
			return current;
		}

		@Override protected ACell view(ACell stored) {
			Object value=viewValue(stored);
			return value==MISSING?null:(ACell)value;
		}

		@Override protected ACell write(ACell current,ACell value) {
			ACell container=current;
			for (ACell key:keys) {
				// Missing containers are refused by assocExisting. In particular, a
				// resolved array index must never turn into an integer object key.
				if (container==null) break;
				if (AInteger.isLong(key) ? !(container instanceof AVector<?>) : !(container instanceof AMap<?,?>)) {
					throw new IllegalArgumentException("JSON path container no longer supports its resolved key");
				}
				container=((ADataStructure<?>)container).get(key);
			}
			return LatticeOps.assocExisting(current,value,keys);
		}
	}

	@Override protected JSONValueComponent<V> rebind(AForkableCursor<V> cursor) {
		return new JSONValueComponent<>(cursor);
	}

	@Override public JSONValueComponent<V> fork() {
		return rebind(storage.fork());
	}
}
