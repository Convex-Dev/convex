package convex.lattice;

import java.util.Objects;

import convex.core.cvm.Address;
import convex.core.data.ACell;
import convex.core.data.ADataStructure;
import convex.core.data.AMap;
import convex.core.data.ASequence;
import convex.core.data.AString;
import convex.core.data.Blobs;
import convex.core.data.Keyword;
import convex.core.data.Path;
import convex.core.data.Symbol;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.ATransformCursor;

/**
 * Addresses a value location as its JSON rendering shows it. Vectors and lists take
 * positions; maps and Indexes take the first existing key a segment names. A segment
 * naming no existing key keeps its string key. Other values have no addressable
 * children, so a path through them reads as absent under the cursor contract.
 *
 * <p>Resolution interprets all keys against one snapshot and binds those keys.
 * Later changes do not reinterpret a resolved key: resolve again to select keys
 * against a new shape. Missing levels do not prescribe future container types.
 * No whole-value JSON validation, merge or container creation is supplied. A
 * resolved component keeps only the selected keys. A write through it whose
 * container is absent, cannot hold its key or has changed kind throws
 * {@link PathWriteException} at that key.</p>
 *
 * @param <V> Value type at this location
 */
public class JSONValueComponent<V extends ACell> extends ValueComponent<V> {

	public JSONValueComponent(AForkableCursor<V> cursor) {
		super(cursor);
	}

	@Override
	public JSONValueComponent<?> resolve(AString... segments) {
		return (JSONValueComponent<?>)super.resolve(segments);
	}

	@Override
	public JSONValueComponent<?> resolve(Path path) {
		return (JSONValueComponent<?>)super.resolve(path);
	}

	@Override
	public JSONValueComponent<?> resolve(AString[] segments,int start,int end) {
		Objects.checkFromToIndex(start,end,segments.length);
		if (start==end) return this;
		int n=end-start;
		ACell[] keys=new ACell[n];
		boolean[] positions=new boolean[n];
		AForkableCursor<V> source=cursor();
		ACell current=source.getOrDefault(null);
		for (int i=0; i<n; i++) {
			AString segment=segments[start+i];
			if (current instanceof ASequence<?> sequence) {
				CVMLong position=position(segment);
				if (position!=null) {
					keys[i]=position;
					positions[i]=true;
					long index=position.longValue();
					current=(index<sequence.count())?sequence.get(index):null;
					continue;
				}
			} else if (current instanceof AMap<?,?> map) {
				ACell key=mapKey(map,segment);
				keys[i]=key;
				current=map.get(key);
				continue;
			}
			keys[i]=segment;
			current=null;
		}
		return new JSONValueComponent<>(new JSONPathCursor(source,keys,positions));
	}

	/** A non-negative decimal position without sign or leading zeroes, or null. */
	private static CVMLong position(AString segment) {
		String text=segment.toString();
		CVMLong position=CVMLong.parse(text);
		if (position==null) return null;
		long index=position.longValue();
		return (index>=0 && Long.toString(index).equals(text))?position:null;
	}

	/**
	 * The first existing key the segment names: the string itself, the keyword or
	 * symbol with that name, then the blob, address or integer Convex parses from it.
	 * Returns the string when none exists.
	 */
	private static ACell mapKey(AMap<?,?> map,AString segment) {
		if (map.containsKey(segment)) return segment;
		ACell key=existing(map,Keyword.create(segment));
		if (key==null) key=existing(map,Symbol.create(segment));
		if (key==null) {
			String text=segment.toString();
			key=existing(map,Blobs.parse(text));
			if (key==null) key=existing(map,Address.parse(text));
			if (key==null) key=existing(map,AInteger.parse(text));
		}
		return (key==null)?segment:key;
	}

	private static ACell existing(AMap<?,?> map,ACell key) {
		return (key!=null && map.containsKey(key))?key:null;
	}

	/** Owns the keys produced by resolution; they never escape to a caller. */
	private static final class JSONPathCursor extends ATransformCursor<ACell,ACell> {
		private final ACell[] keys;

		/** Which keys are sequence positions; the others are map keys, integers included. */
		private final boolean[] positions;

		@SuppressWarnings("unchecked")
		JSONPathCursor(AForkableCursor<?> source,ACell[] keys,boolean[] positions) {
			super((AForkableCursor<ACell>)source);
			this.keys=keys;
			this.positions=positions;
		}

		@Override protected Object viewValue(ACell stored) {
			ACell current=stored;
			for (int i=0; i<keys.length; i++) {
				if (positions[i]) {
					if (!(current instanceof ASequence<?> sequence)) return MISSING;
					long index=((CVMLong)keys[i]).longValue();
					if (index>=sequence.count()) return MISSING;
					current=sequence.get(index);
				} else {
					if (!(current instanceof AMap<?,?> map)) return MISSING;
					ACell value=map.get(keys[i]);
					if (value==null && !map.containsKey(keys[i])) return MISSING;
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
			for (int i=0; i<keys.length && container!=null; i++) {
				// Missing containers are refused by assocExisting. A position must never
				// become a map key, nor an integer map key a sequence position.
				if (!(positions[i] ? container instanceof ASequence<?> : container instanceof AMap<?,?>)) {
					throw new PathWriteException("Cannot write the key: its container is no longer the kind it was resolved against",keys,i);
				}
				container=((ADataStructure<?>)container).get(keys[i]);
			}
			return LatticeOps.assocExisting(current,value,keys);
		}
	}

	@Override public JSONValueComponent<V> rebind(AForkableCursor<V> cursor) {
		return new JSONValueComponent<>(cursor);
	}

	@Override public JSONValueComponent<V> fork() {
		return rebind(storage.fork());
	}
}
