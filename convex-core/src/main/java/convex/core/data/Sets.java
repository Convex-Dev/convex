package convex.core.data;

import java.util.ArrayList;
import java.util.Collection;

import convex.core.exceptions.BadFormatException;
import convex.core.lang.RT;
import convex.core.util.Utils;

public class Sets {
	
	static final Ref<?>[] EMPTY_ENTRIES = new Ref[0];

	@SuppressWarnings({ "rawtypes", "unchecked" })
	static final SetLeaf EMPTY = Cells.intern(new SetLeaf(EMPTY_ENTRIES));
	
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
			result=(ASet<T>) result.conj((T) elements[i]);
		}
		return result;
 	}

	/**
	 * Creates a set of all the elements in the given data structure
	 * 
	 * @param <T> Type of elements
	 * @param source Source for elements
	 * @return A Set
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> ASet<T> create(ACountable<T> source) {
		if (source==null) return EMPTY;
		if (source instanceof ASet) return (ASet<T>) source;

		if (source instanceof AMap) {
			ASequence<T> seq = RT.sequence(source); // should always be non-null
			return Sets.create(seq);
		}
		if (source instanceof ACountable) return Sets.fromCollection(source);
		throw new IllegalArgumentException("Unexpected type!" + Utils.getClass(source));
	}
	
	/**
	 * Creates a set of all the elements in the given data structure
	 * 
	 * @param <T> Type of elements
	 * @param source Source for elements
	 * @return A Set
	 */
	public static <T extends ACell> ASet<T> fromCollection(Collection<T> source) {
		return Sets.of(source.toArray());
	}
	
	/**
	 * Creates a set of all the elements in the given data structure
	 * 
	 * @param <T> Type of elements
	 * @param source Source for elements
	 * @return A Set
	 */
	@SuppressWarnings("unchecked")
	private static <T extends ACell> ASet<T> fromCollection(ACountable<T> source) {
		long n=source.count();
		ASet<T> set=EMPTY;
		for (long i=0; i<n; i++) {
			set=set.include(source.get(i));
		}
		return (ASet<T>) set;
	}

	public static <T extends ACell> ASet<T> read(Blob b, int pos) throws BadFormatException {
		long count = Format.readVLCLong(b,pos+1);
		if (count <= SetLeaf.MAX_ELEMENTS) {
			return SetLeaf.read(b, pos, count);
		} else {
			return SetTree.read(b, pos, count);
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
