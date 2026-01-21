package convex.lattice;

import convex.core.data.ACell;
import convex.core.data.Cells;
import convex.core.lang.RT;

/**
 * Abstract base class for lattice functions
 * 
 * Lattices represent merge functions for lattice values and support:
 * - a `zero` initial value
 * - ability to validate foreign values (pre-merge checks)
 * - ability to obtain child lattices
 * 
 * @param <V> Type of values in this lattice
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
	 * Context-aware merge function. Default implementation delegates to simple merge.
	 * Override this method if merge logic requires contextual information (timestamp, signing key, etc.)
	 *
	 * @param context Context for merge operation
	 * @param ownValue Own lattice value
	 * @param otherValue Externally received lattice value
	 * @return Merged lattice root cell
	 */
	public V merge(LatticeContext context, V ownValue, V otherValue) {
		return merge(ownValue, otherValue);
	}
	
	/**
	 * Obtains the "zero" value for the lattice. This may be null, but a non-null zero value is preferred.
	 * 
	 * @return Zero value of the lattice. 
	 */
	public abstract V zero();

	/**
	 * Check if a foreign value is legal. Subtypes must check validity as far as any child lattices.
	 * 
	 * @param value Value received from foreign source
	 * @return true if foreign value is an acceptable lattice value
	 */
	public abstract boolean checkForeign(V value);
	
	/**
	 * Get the sub-lattice at the specified path
	 * @param <T>
	 * @param path Path of ACell keys
	 * @return Sub-lattice instance, or null if invalid path
	 */
	public final  <T extends ACell> ALattice<T> path(ACell... path) {
		return path(path,0);
	}
	
	/**
	 * Get this lattice (with an empty path)
	 * @return This lattice cast to specified type
	 */
	public ALattice<V> path() {
		return this;
	}
	
	/**
	 * Get a child lattice
	 * @return The child lattice (may be null)
	 */
	public abstract <T extends ACell> ALattice<T> path(ACell childKey);
	
	@SuppressWarnings("unchecked")
	protected <T extends ACell> ALattice<T> path(ACell[] path, int pos) {
		if (path.length<=pos) return (ALattice<T>) this;
		ALattice<?> child=path(path[pos]);
		if (child==null) return null;
		return child.path(path,pos+1);
	}
	
	/**
	 * Get this lattice (with an empty path)
	 * @return This lattice cast to specified type
	 */
	public <T extends ACell> ALattice<T> path(Object childKey) {
		return path(RT.cvm(childKey));
	}

	
	/**
	 * Get the sub-lattice at the specified path
	 * @param <T>
	 * @param path Path of keys
	 * @return Sub-lattice instance, or null if invalid path
	 */
	@SuppressWarnings("unchecked")
	public <T extends ACell> ALattice<T> path(Object... path) {
		int d=path.length;
		if (d==0) return (ALattice<T>) path();
		if (d==1) return (ALattice<T>) path((ACell)RT.cvm(path[0]));
		
		ACell[] cellPath=Cells.toCellArray(path);
		return path(cellPath,0);
	}

}
