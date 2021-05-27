package convex.core.data;

/**
 * Abstract base class for Persistent data structures. Each can be regarded as a
 * countable, immutable collection of elements.
 * 
 * Data structures in general support:
 * <ul>
 * <li> Immutability</li>
 * <li> Addition of an element(s) of appropriate type </li>
 * <li> Construction of an empty (zero) element </li>
 * </ul>
 * 
 * <p>
 * "When you know your data can never change out from underneath you, everything
 * is different." - Rich Hickey
 * </p>
 */
public abstract class ADataStructure<E extends ACell> extends ACountable<E> {
	/**
	 * Returns an empty instance of the same general type as this data structure.
	 * 
	 * @return An empty data structure
	 */
	public abstract ADataStructure<E> empty();

	/**
	 * Adds an element to this data structure, in the natural manner defined by the
	 * general data structure type. e.g. append at the end of a vector.
	 * 
	 * @param x New element to add
	 * @return The updated data structure, or null if a failure occurred due to invalid element type
	 */
	public abstract <R extends ACell> ADataStructure<R> conj(R x);
	
	/**
	 * Adds multiple elements to this data structure, in the natural manner defined by the
	 * general data structure type. e.g. append at the end of a vector.
	 * 
	 * This may be more efficient than using 'conj' for individual items.
	 * 
	 * @param xs New elements to add
	 * @return The updated data structure, or null if a failure occurred due to invalid elementtypes
	 */
	@SuppressWarnings("unchecked")
	public <R extends ACell> ADataStructure<R> conjAll(ACollection<R> xs) {
		ADataStructure<R> result=(ADataStructure<R>) this;
		for (R x: xs) {
			result=result.conj(x);
			if (result==null) return null;
		}
		return result;
	}
	


}
