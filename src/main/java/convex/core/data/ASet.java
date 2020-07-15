package convex.core.data;

import java.util.Collection;
import java.util.Set;

import convex.core.crypto.Hash;

/**
 * Abstract based class for sets.
 * 
 * Sets are immutable Smart Data Structures representing an unordered
 * collection of distinct values.
 * 
 * Iteration order is dependent on the Set implementation. In general, it
 * is bad practice to depend on any specific ordering for sets.
 *
 * @param <T> Type of set elements
 */
public abstract class ASet<T> extends ACollection<T> implements Set<T>, IGet<T> {
	
	@Override
	public abstract void ednString(StringBuilder sb) ;

	/**
	 * Updates the set to include the given element
	 * @param a
	 * @return Updated set
	 */
	public abstract ASet<T> include(T a);
	
	/**
	 * Updates the set to exclude the given element
	 * @param a
	 * @return Updated set
	 */
	public abstract ASet<T> exclude(T a) ;
	
	/**
	 * Updates the set to include all the given elements.
	 * Can be used to implement union of sets
	 * 
	 * @param elements
	 * @return Updated set
	 */
	public abstract ASet<T> includeAll(Collection<T> elements) ;
	
	/**
	 * Updates the set to exclude all the given elements.
	 * 
	 * @param elements
	 * @return Updated set
	 */
	public abstract ASet<T> excludeAll(Collection<T> elements) ;

	@SuppressWarnings("unchecked")
	@Override
	public T get(Object key) {
		if (contains(key)) return (T) key;
		return null;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public T get(Object key, Object notFound) {
		if (contains(key)) return (T) key;
		return (T) notFound;
	}
	
	@Override
	public final boolean containsKey(Object o) {
		return contains(o);
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public final boolean equals(Object o) {
		if (o instanceof ASet) return equals((ASet<T>)o);
		return false;
	}
	
	/**
	 * Checks if another set is exactly equal to this set
	 * 
	 * @param other Set to compare with this set
	 * @return true if sets are equal, false otherwise
	 */
	public abstract boolean equals(ASet<T> other);

	/**
	 * Adds a value to this set using a Ref to the value
	 * @param ref
	 * @return Updated set
	 */
	public abstract ASet<T> includeRef(Ref<T> ref) ;

	@SuppressWarnings("unchecked")
	@Override
	public ASet<T> conj(Object a) {
		return include((T) a);
	}

	/**
	 * Gets the Object in the set for the given hash, or null if not found
	 * @param hash
	 * @return The set value for the given Hash if found, null otherwise.
	 */
	public abstract Object getByHash(Hash hash) ;
	
	@Override
	public ASet<T> empty() {
		return Sets.empty();
	}
}
