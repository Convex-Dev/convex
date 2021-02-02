package convex.core.data;

import java.util.Collection;

import convex.core.lang.RT;
import convex.core.util.Utils;

public class Sets {

	@SuppressWarnings("unchecked")
	public static <T extends ACell> Set<T> empty() {
		return (Set<T>) Set.EMPTY;
	}

	@SafeVarargs
	public static <T extends ACell> Set<T> of(Object... elements) {
		return Set.create(elements);
	}

	@SuppressWarnings("unchecked")
	public static <T extends ACell> ASet<T> create(Object source) {
		if (source instanceof ADataStructure) return create((ADataStructure<T>) source);
		if (source instanceof Collection) {
			Object[] elements = (Object[]) ((Collection<T>) source).toArray();
			return Set.create(elements);
		}
		throw new Error("Unexpected type!" + Utils.getClass(source));
	}

	/**
	 * Creates a set of all the elements in the goven data structure
	 * 
	 * @param <T>
	 * @param source
	 * @return A Set
	 */
	public static <T extends ACell> Set<T> create(ADataStructure<T> source) {
		if (source instanceof Set) return (Set<T>) source;
		if (source instanceof ASequence) return Set.create((ASequence<T>) source);
		if (source instanceof AMap) {
			ASequence<T> seq = RT.sequence(source); // should always be non-null
			Set.create(seq);
		}
		throw new IllegalArgumentException("Unexpected type!" + Utils.getClass(source));
	}
}
