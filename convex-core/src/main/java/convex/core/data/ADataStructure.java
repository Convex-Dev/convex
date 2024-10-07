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
	 * Returns an empty instance of the same Type as this data structure.
	 * 
	 * @return An empty data structure
	 */
	@Override
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
	public abstract ADataStructure<E> conj(ACell x);
	
	/**
	 * Adds multiple elements to this data structure, in the natural manner defined by the
	 * general data structure type. e.g. append at the end of a vector.
	 * 
	 * This may be more efficient than using 'conj' for individual items.
	 * 
	 * @param xs New elements to add
	 * @return The updated data structure, or null if a failure occurred due to invalid element types
	 */
	public ADataStructure<E> conjAll(ACollection<? extends E> xs) {
		ADataStructure<E> result=this;
		for (E x: xs) {
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
	 * @param key Associative key
	 * @param value Value to associate with key
	 * @return Updates data structure, or null if data types are invalid
	 */
	public abstract ADataStructure<E> assoc(ACell key,ACell value);
	
	/**
	 * Get the value associated with a given key.
	 * 
	 * @param key Associative key to look up
	 * @return Value from collection, or a falsey value (null or false) if not found
	 */
	public abstract ACell get(ACell key);
	
	/**
	 * Get the value associated with a given key.
	 * 
	 * @param key Key to look up in data structure
	 * @param notFound Value to return if key is not found
	 * @return Value from collection, or notFound value if not found
	 */
	public abstract ACell get(ACell key, ACell notFound);

	
	/**
	 * Checks if the data structure contains the specified key
	 * 
	 * @param key Associative key to look up
	 * @return true if the data structure contains the key, false otherwise
	 */
	public abstract boolean containsKey(ACell key);
	
	/**
	 * Converts CVM data structure to a CVM String, as per 'print'
	 */
	@Override
	public AString toCVMString(long limit) {
		return print(limit);
	}
	
	/**
	 * Checks if the given index is in range for this data structure
	 * @param ix Index to check
	 * @throws IndexOutOfBoundsException if index is invalid
	 */
	public final void checkIndex(long ix) {
		if ((ix>=0)&&(ix<count)) return;
		throw new IndexOutOfBoundsException((int)ix);
	}

}
