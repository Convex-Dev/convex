package convex.core.data;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;

import convex.core.exceptions.BadFormatException;
import convex.core.lang.RT;
import convex.core.util.Utils;

public class Sets {
	
	static final Ref<?>[] EMPTY_ENTRIES = new Ref[0];

	@SuppressWarnings({ "rawtypes", "unchecked" })
	static final SetLeaf EMPTY = new SetLeaf(EMPTY_ENTRIES);
	
	@SuppressWarnings("rawtypes")
	public static final Ref<SetLeaf> EMPTY_REF = EMPTY.getRef();

	@SuppressWarnings("unchecked")
	public static <T extends ACell> SetLeaf<T> empty() {
		return (SetLeaf<T>) EMPTY;
	}
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static <T extends ACell> Ref<SetLeaf<T>> emptyRef() {
		return (Ref)EMPTY_REF;
	}

	@SuppressWarnings("unchecked")
	@SafeVarargs
	public static <T extends ACell> ASet<T> of(Object... elements) {
		int n=elements.length;
		ASet<T> result=empty();
		for (int i=0; i<n; i++) {
			result=(ASet<T>) result.conj(RT.cvm(elements[i]));
		}
		return result;
	}
	
	@SuppressWarnings("unchecked")
	@SafeVarargs
	public static <T extends ACell> ASet<T> of(ACell... elements) {
		int n=elements.length;
		ASet<T> result=empty();
		for (int i=0; i<n; i++) {
			result=(ASet<T>) result.conj(elements[i]);
		}
		return result;
 	}

	/**
	 * Creates a set of all the elements in the given data structure
	 * 
	 * @param <T>
	 * @param source
	 * @return A Set
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> ASet<T> create(ADataStructure<T> source) {
		if (source instanceof ASet) return (ASet<T>) source;

		if (source instanceof AMap) {
			ASequence<T> seq = RT.sequence(source); // should always be non-null
			return Sets.create(seq);
		}
		if (source instanceof ACollection) return Sets.fromCollection((Collection<T>) source);
		throw new IllegalArgumentException("Unexpected type!" + Utils.getClass(source));
	}
	
	/**
	 * Creates a set of all the elements in the given data structure
	 * 
	 * @param <T>
	 * @param source
	 * @return A Set
	 */
	public static <T extends ACell> ASet<T> fromCollection(Collection<T> source) {
		return Sets.of(source.toArray());
	}

	public static <T extends ACell> ASet<T> read(ByteBuffer bb) throws BadFormatException {
		long count = Format.readVLCLong(bb);
		if (count <= SetLeaf.MAX_ENTRIES) {
			return SetLeaf.read(bb, count);
		} else {
			return SetTree.read(bb, count);
		}
	}

	public static <T extends ACell> AHashSet<T> createWithShift(int shift, ArrayList<Ref<T>> values) {
		AHashSet<T> result=Sets.empty();
		for (Ref<T> v: values) {
			result=result.includeRef(v, shift);
		}
		return result;
	}


}
