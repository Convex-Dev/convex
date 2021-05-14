package convex.core.data;

/**
 * Abstract base class for Persistent data structures. Each can be regarded as a
 * countable, immutable collection of elements.
 * 
 * Data structures in general support:
 * <ul>
 * <li> Immutability</li>
 * <li> Count of elements</li>
 * <li> Addition of an element of appropriate type </li>
 * <li> Construction of an empty (zero) element </li>
 * </ul>
 * 
 * <p>
 * "When you know your data can never change out from underneath you, everything
 * is different." - Rich Hickey
 * </p>
 */
public abstract class ADataStructure<E extends ACell> extends ACell {

	/**
	 * Returns the number of elements in this data structure
	 * 
	 * @return Number of elements in this collection.
	 */
	public abstract long count();

	/**
	 * Gets the size of this data structure as an int.
	 * 
	 * Returns Integer.MAX_SIZE if the count is larger than can fit in an int. If
	 * this might be a problem, use count() instead.
	 * 
	 * @return Number of elements in this collection.
	 */
	public int size() {
		return (int) (Math.min(count(), Integer.MAX_VALUE));
	}

	/**
	 * Checks if this data structure is empty, i.e. has a count of zero elements.
	 * 
	 * @return true if this data structure is empty, false otherwise
	 */
	public boolean isEmpty() {
		return count() == 0L;
	}

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
	
	/**
	 * Gets the element at the specified index in this collection
	 * 
	 * @param index Index of element to get
	 * @return Element at the specified index
	 */
	public abstract E get(long i);

}
