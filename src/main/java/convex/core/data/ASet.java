package convex.core.data;

import convex.core.Constants;
import convex.core.data.prim.CVMBool;
import convex.core.data.type.AType;
import convex.core.data.type.Types;

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
public abstract class ASet<T extends ACell> extends ACollection<T> implements java.util.Set<T>, IAssociative<T,CVMBool> {
	
	@Override
	public final AType getType() {
		return Types.SET;
	}
	
	@Override
	public abstract void ednString(StringBuilder sb) ;

	/**
	 * Updates the set to include the given element
	 * @param a
	 * @return Updated set
	 */
	public abstract <R extends ACell> ASet<R> include(R a);
	
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
	public abstract <R extends ACell> ASet<R> includeAll(Set<R> elements) ;
	
	/**
	 * Updates the set to exclude all the given elements.
	 * 
	 * @param elements
	 * @return Updated set
	 */
	public abstract ASet<T> excludeAll(Set<T> elements) ;

	@Override
	public abstract <R extends ACell> ASet<R> conjAll(ACollection<R> xs);

	/**
	 * Removes all elements from this set, returning a new set.
	 * @param xs Collection of elements to remove
	 * @return
	 */
	public abstract ASet<T> disjAll(ACollection<T> xs);

	/**
	 * Returns the intersection of two sets
	 * @param xs
	 * @return
	 */
	public abstract ASet<T> intersectAll(ASet<T> xs);

	@Override
	public CVMBool get(ACell key) {
		return contains(key)?Constants.SET_INCLUDED:Constants.SET_EXCLUDED;
	}
	
	@Override
	public ACell get(ACell key, ACell notFound) {
		if (contains(key)) return Constants.SET_INCLUDED;
		return notFound;
	}
	
	@Override
	public final boolean containsKey(ACell o) {
		return contains(o);
	}
	

	
	@SuppressWarnings("unchecked")
	@Override
	public final boolean equals(ACell o) {
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
	public abstract <R extends ACell> ASet<R> includeRef(Ref<R> ref) ;

	@Override
	public abstract <R extends ACell> ASet<R> conj(R a);

	/**
	 * Gets the Object in the set for the given hash, or null if not found
	 * @param hash
	 * @return The set value for the given Hash if found, null otherwise.
	 */
	public abstract ACell getByHash(Hash hash) ;
	
	@Override
	public ASet<T> empty() {
		return Sets.empty();
	}
	
	/**
	 * Tests if this set is a (non-strict) subset of another Set
	 * @param b Set to test against
	 * @return True if this is a subset of the other set, false otherwise.
	 */
	public abstract boolean isSubset(Set<T> b);
}
