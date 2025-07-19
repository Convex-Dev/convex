package convex.lattice;

import convex.core.data.ACell;

/**
 * Abstract base class for lattice components
 */
public abstract class ALattice<V extends ACell> {

	/**
	 * Implementation of merge function
	 * @param ownValue Own lattice value
	 * @param otherValue Externally received lattice value
	 * @return Merged lattice root cell
	 */
	public abstract V merge(V ownValue, V otherValue);
	
	/**
	 * Obtains the "zero" value for the lattice. This may be null, but a non-null zero value is preferred.
	 * 
	 * @return Zero value of the lattice. 
	 */
	public abstract V zero();

	/**
	 * Check if a foreign value is legal. Subtypes should check validity as far as any child lattices.
	 * 
	 * @param value Value received from foreign source
	 * @return true if foreign value is an acceptable lattice value
	 */
	public abstract boolean checkForeign(V value);
}
