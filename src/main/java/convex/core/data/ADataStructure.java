package convex.core.data;

/**
 * Abstract base class for Persistent data structures. Each can be regarded as a
 * countable, immutable collection of elements.
 * 
 * "When you know your data can never change out from underneath you, everything
 * is different." - Rich Hickey
 */
public abstract class ADataStructure<E> extends ACell {

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
	
	@Override
	public boolean isEmbedded() {
		// Logic: only empty data structures are embedded
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
	 * @return The updated data structure.
	 */
	public abstract <R> ADataStructure<R> conj(R x);

}
