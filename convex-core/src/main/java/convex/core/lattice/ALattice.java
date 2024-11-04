package convex.core.lattice;

import convex.core.data.ACell;

/**
 * Abstract base class for lattice components
 */
public abstract class ALattice {

	/**
	 * Implementation of merge function
	 * @param ownValue Own lattice value
	 * @param otherValue Externally received lattice value
	 * @return
	 */
	public abstract ACell merge(ACell ownValue, ACell otherValue);
}
