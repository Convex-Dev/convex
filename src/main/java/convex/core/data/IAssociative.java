package convex.core.data;

/**
 * Interface for associative data structures
 *
 * @param <K>
 * @param <V>
 */
public interface IAssociative<K,V> extends IGet<V> {

	/**
	 * Associates a key with a value in this associative data structure.
	 * 
	 * May throw an exception if the Key or Value is incompatible with the data structure.
	 * 
	 * @param key
	 * @param value
	 * @return
	 */
	public ADataStructure<?> assoc(K key,V value);
}
