package convex.core.data;

import java.nio.ByteBuffer;
import java.util.Collection;

import convex.core.exceptions.BadFormatException;
import convex.core.lang.RT;
import convex.core.util.Utils;

public class Sets {

	@SuppressWarnings("unchecked")
	public static <T extends ACell> SetLeaf<T> empty() {
		return (SetLeaf<T>) SetLeaf.EMPTY;
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
	public static <T extends ACell> ASet<T> create(ADataStructure<T> source) {
		if (source instanceof ASet) return (ASet<T>) source;
		if (source instanceof ASequence) return Sets.create((ASequence<T>) source);
		if (source instanceof AMap) {
			ASequence<T> seq = RT.sequence(source); // should always be non-null
			return Sets.create(seq);
		}
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
}
