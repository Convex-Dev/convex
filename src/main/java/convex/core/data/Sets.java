package convex.core.data;

import convex.core.lang.RT;
import convex.core.util.Utils;

public class Sets {

	@SuppressWarnings("unchecked")
	public static <T extends ACell> Set<T> empty() {
		return (Set<T>) Set.EMPTY;
	}

	@SafeVarargs
	public static <T extends ACell> Set<T> of(Object... elements) {
		return Set.of(elements);
	}
	
	@SafeVarargs
	public static <T extends ACell> Set<T> of(ACell... elements) {
		return Set.create(elements);
	}

	/**
	 * Creates a set of all the elements in the given data structure
	 * 
	 * @param <T>
	 * @param source
	 * @return A Set
	 */
	public static <T extends ACell> Set<T> create(ADataStructure<T> source) {
		if (source instanceof ASet) return (Set<T>) source;
		if (source instanceof ASequence) return Set.create((ASequence<T>) source);
		if (source instanceof AMap) {
			ASequence<T> seq = RT.sequence(source); // should always be non-null
			return Set.create(seq);
		}
		throw new IllegalArgumentException("Unexpected type!" + Utils.getClass(source));
	}
}
