package convex.lattice;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.ASequence;
import convex.core.data.Cells;
import convex.core.data.Keyword;
import convex.core.data.Strings;
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
	 * Resolves an external key (e.g. JSON string) to the canonical CVM key
	 * used by this lattice level. Returns null if the key cannot be resolved
	 * to a valid child key.
	 *
	 * <p>Used by JSON-based APIs to translate path elements before calling
	 * standard lattice operations like {@code descend()}. Normal CVM code
	 * that already uses canonical key types does not need this.
	 *
	 * <p>Default implementation returns the key unchanged.
	 *
	 * @param key External key to resolve
	 * @return Canonical CVM key, or null if the key is not valid
	 */
	public ACell resolveKey(ACell key) {
		return key;
	}

	/**
	 * Converts a canonical CVM key to its JSON-compatible representation.
	 * Keywords become their name strings; blobs become hex strings;
	 * other types (AString, AInteger) pass through unchanged.
	 *
	 * @param key CVM key to convert
	 * @return JSON-compatible key
	 */
	public static ACell toJSONKey(ACell key) {
		if (key instanceof Keyword k) return k.getName();
		if (key instanceof ABlob b) return Strings.create(b.toHexString());
		return key;
	}

	/**
	 * Resolves a JSON path (array of external keys) to canonical CVM keys by
	 * walking the lattice hierarchy. Each key is resolved via {@link #resolveKey}
	 * at the appropriate lattice level.
	 *
	 * @param jsonPath External keys to resolve
	 * @return Array of canonical CVM keys, or null if resolution fails at any level
	 */
	public ACell[] resolvePath(ACell... jsonPath) {
		int n=jsonPath.length;
		ACell[] result=new ACell[n];
		ALattice<?> current=this;
		for (int i=0; i<n; i++) {
			ACell resolved=current.resolveKey(jsonPath[i]);
			if (resolved==null) return null;
			result[i]=resolved;
			if (i<n-1) {
				current=current.path(resolved);
				if (current==null) return null;
			}
		}
		return result;
	}

	/**
	 * Resolves a JSON path (sequence of external keys) to canonical CVM keys by
	 * walking the lattice hierarchy. Each key is resolved via {@link #resolveKey}
	 * at the appropriate lattice level.
	 *
	 * @param jsonPath Sequence of external keys to resolve
	 * @return Array of canonical CVM keys, or null if resolution fails at any level
	 */
	public ACell[] resolvePath(ASequence<ACell> jsonPath) {
		int n=(int)jsonPath.count();
		ACell[] result=new ACell[n];
		ALattice<?> current=this;
		for (int i=0; i<n; i++) {
			ACell resolved=current.resolveKey(jsonPath.get(i));
			if (resolved==null) return null;
			result[i]=resolved;
			if (i<n-1) {
				current=current.path(resolved);
				if (current==null) return null;
			}
		}
		return result;
	}

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
