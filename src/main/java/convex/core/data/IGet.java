package convex.core.data;

/**
 * Interface for associative data structure supporting get(Object);
 * 
 */
public interface IGet<V> {

	/**
	 * Get the value associated with a given key.
	 * 
	 * @param key
	 * @return Value from collection, or null if not found
	 */
	public V get(Object key);

	/**
	 * Get the value associated with a given key.
	 * 
	 * @param key
	 * @return Value from collection, or notFound value if not found
	 */
	public V get(Object key, Object notFound);

	/**
	 * Checks if the data structure contains the specified key
	 * 
	 * @param key
	 * @return true if the data structure contains the key, false otherwise
	 */
	public boolean containsKey(ACell key);

}
