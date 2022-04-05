package convex.core.data;

/**
 * Abstract base class for Countable objects.
 * 
 * Countable values support a count of elements and the ability to get by an element index.
 * 
 * @param <E> Type of element that is counted
 */
public abstract class ACountable<E extends ACell> extends ACell {
	
	/**
	 * Returns the number of elements in this data structure
	 * 
	 * @return Number of elements in this collection.
	 */
	public abstract long count();

	/**
	 * Gets the element at the specified element index in this collection
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
}
