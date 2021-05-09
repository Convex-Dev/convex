package convex.core.data;

/**
 * Interface for associative data structures
 *
 * @param <K>
 * @param <V>
 */
public interface IAssociative<K extends ACell,V extends ACell> extends IGet<V> {

	/**
	 * Associates a key with a value in this associative data structure.
	 * 
	 * May return null if the Key or Value is incompatible with the data structure.
	 * 
	 * @param key
	 * @param value
	 * @return Updates data structure, or null if data types are invalid
	 */
	public ADataStructure<?> assoc(ACell key,V value);
}
