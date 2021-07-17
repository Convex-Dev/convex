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
 * 
 * @param <E> Type of Data Structure elements
 */
public abstract class ADataStructure<E extends ACell> extends ACountable<E> {
	
	protected final long count;
	
	protected ADataStructure(long count) {
		this.count=count;
	}
	
	/**
	 * Gets the count of elements in this data structure
	 */
	@Override
	public final long count() {
		return count;
	}
	
	@Override
	public final int size() {
		return (int) (Math.min(count, Integer.MAX_VALUE));
	}
	
	/**
	 * Returns an empty instance of the same general type as this data structure.
	 * 
	 * @return An empty data structure
	 */
	public abstract ADataStructure<E> empty();
	
	@Override
	public final boolean isEmpty() {
		return count==0L;
	}

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
	 * Associates a key with a value in this associative data structure.
	 * 
	 * May return null if the Key or Value is incompatible with the data structure.
	 * 
	 * @param key
	 * @param value
	 * @return Updates data structure, or null if data types are invalid
	 */
	public abstract ADataStructure<E> assoc(ACell key,ACell value);
	
	/**
	 * Get the value associated with a given key.
	 * 
	 * @param key
	 * @return Value from collection, or null if not found
	 */
	public abstract ACell get(ACell key);
	
	/**
	 * Get the value associated with a given key.
	 * 
	 * @param key Key to look up in data structure
	 * @param notFound Value to retun if key is not found
	 * @return Value from collection, or notFound value if not found
	 */
	public abstract ACell get(ACell key, ACell notFound);

	
	/**
	 * Checks if the data structure contains the specified key
	 * 
	 * @param key
	 * @return true if the data structure contains the key, false otherwise
	 */
	public abstract boolean containsKey(ACell key);

}
