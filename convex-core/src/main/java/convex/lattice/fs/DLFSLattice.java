package convex.lattice.fs;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;
import convex.lattice.ALattice;
import convex.lattice.generic.IndexLattice;

/**
 * Lattice implementation for DLFS (Data Lattice FileSystem) nodes.
 * 
 * This lattice provides rsync-like merge semantics between two filesystem trees:
 * - Directories are merged recursively by entry name
 * - Files are merged based on timestamp (newer wins)
 * - Missing entries from one side are added from the other
 * - Tombstones (deleted files) are preserved appropriately
 * 
 * The merge behaviour is equivalent to Unix rsync between two drives, where:
 * - Files/directories that exist in both are merged
 * - Files/directories that exist only in one are copied to the result
 * - Conflicts are resolved by timestamp (newer wins)
 */
public class DLFSLattice extends ALattice<AVector<ACell>> {

	/**
	 * Singleton instance of DLFSLattice
	 */
	public static final DLFSLattice INSTANCE = new DLFSLattice();

	/**
	 * Lattice for directory entries (Index&lt;AString, AVector&lt;ACell&gt;&gt;).
	 * Merges entries using DLFSLattice for child node values.
	 */
	static final IndexLattice<AString, AVector<ACell>> DIR_ENTRIES_LATTICE =
		IndexLattice.create(INSTANCE);

	private DLFSLattice() {
		// Private constructor for singleton
	}

	@Override
	public AVector<ACell> merge(AVector<ACell> ownValue, AVector<ACell> otherValue) {
		// Handle null cases
		if (ownValue == null) {
			if (checkForeign(otherValue)) {
				return otherValue;
			}
			return zero();
		}
		if (otherValue == null) {
			return ownValue;
		}

		// Fast path: if values are equal, return own value
		if (Utils.equals(ownValue, otherValue)) {
			return ownValue;
		}

		// Delegate to DLFSNode.merge which implements the rsync-like merge logic
		// The merge is deterministic: timestamp is derived from the input nodes
		return DLFSNode.merge(ownValue, otherValue);
	}

	@Override
	public AVector<ACell> merge(convex.lattice.LatticeContext context, AVector<ACell> ownValue, AVector<ACell> otherValue) {
		// Handle null cases
		if (ownValue == null) {
			if (checkForeign(otherValue)) {
				return otherValue;
			}
			return zero();
		}
		if (otherValue == null) {
			return ownValue;
		}

		// Fast path: if values are equal, return own value
		if (Utils.equals(ownValue, otherValue)) {
			return ownValue;
		}

		// Delegate to DLFSNode.merge which implements the rsync-like merge logic
		// The merge is deterministic: timestamp is derived from the input nodes
		// Note: Context timestamp is currently not used for DLFS merge.
		// If timestamp override is needed, it should be handled at a higher level.
		return DLFSNode.merge(ownValue, otherValue);
	}

	@Override
	public AVector<ACell> zero() {
		// Zero value is an empty directory
		return DLFSNode.createDirectory(CVMLong.ZERO);
	}

	@Override
	public boolean checkForeign(AVector<ACell> value) {
		if (value == null) {
			return false;
		}
		
		// Check that it's a valid DLFS node structure
		// A valid DLFS node is a vector with at least NODE_LENGTH elements
		if (!(value instanceof AVector)) {
			return false;
		}
		
		// Check minimum length (should have at least NODE_LENGTH elements)
		if (value.count() < DLFSNode.NODE_LENGTH) {
			return false;
		}
		
		// Additional validation: check that timestamp is present and valid
		ACell utime = value.get(DLFSNode.POS_UTIME);
		if (!(utime instanceof CVMLong)) {
			return false;
		}
		
		// Valid DLFS node structure
		return true;
	}

	@Override
	public ACell resolveKey(ACell key) {
		// DLFS node is a vector — only integer keys are valid at this level
		if (key instanceof AInteger) return key;
		if (key instanceof AString) {
			// Try parsing as integer for vector position access (e.g. "0" → 0)
			AInteger n = AInteger.parse(key);
			if (n != null) return n;
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		// DLFS node value is AVector<ACell> — only integer indices are valid
		if (childKey instanceof AInteger) {
			long idx = ((AInteger) childKey).longValue();
			if (idx == DLFSNode.POS_DIR) {
				// Position 0: directory entries (Index<AString, AVector<ACell>>)
				// Navigate further with path(AString) on the IndexLattice
				return (ALattice<T>) DIR_ENTRIES_LATTICE;
			}
			// Other positions (1=data, 2=metadata, 3=utime) are leaf values
			return null;
		}
		return null;
	}

}
