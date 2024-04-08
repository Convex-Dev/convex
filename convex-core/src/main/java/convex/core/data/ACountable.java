package convex.core.data;

/**
 * Abstract base class for Countable objects, supporting `count`, `nth`, `empty?` and `slice`
 * 
 * Countable values support a count of elements and the ability to get by an element index.
 * 
 * @param <E> Type of element that is counted
 */
public abstract class ACountable<E extends ACell> extends ACell {
	
	/**
	 * Returns the number of elements in this value
	 * 
	 * @return Number of elements
	 */
	public abstract long count();

	/**
	 * Gets the element at the specified element index in this value
	 * 
	 * @param index Index of element to get
	 * @return Element at the specified index
	 */
	public abstract E get(long index);
	

	/**
	 * Gets a Ref to the element at the specified element index in this collection
	 * 
	 * @param index Index of element to get
	 * @return Element at the specified index
	 */
	public abstract Ref<E> getElementRef(long index);
	
	/**
	 * Checks if this data structure is empty, i.e. has a count of zero elements.
	 * 
	 * @return true if this data structure is empty, false otherwise
	 */
	public boolean isEmpty() {
		return count() == 0L;
	}
	
	/**
	 * Returns a canonical, singleton empty instance of the same type as this Countable value.
	 * 
	 * @return An empty Countable value, or null if there is no empty instance
	 */
	public abstract ACountable<E> empty();
	
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
	 * Gets a slice of this data structure
	 * @param start Start index (inclusive)
	 * @param end end index (exclusive)
	 * @return Slice of data structure, or null if invalid slice
	 */
	public abstract ACountable<E> slice(long start, long end);
	
	/**
	 * Gets a slice of this data structure from start to the end
	 * @param start Start index (inclusive)
	 * @return Slice of data structure, or null if invalid slice
	 */
	public ACountable<E> slice(long start) {
		return slice(start, count());
	}
}
