package convex.lattice.fs;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;
import convex.lattice.ALattice;

/**
 * Lattice implementation for DLFS (Data Lattice FileSystem) nodes.
 * 
 * This lattice provides rsync-like merge semantics between two filesystem trees:
 * - Directories are merged recursively by entry name
 * - Files are merged based on timestamp (newer wins)
 * - Missing entries from one side are added from the other
 * - Tombstones (deleted files) are preserved appropriately
 * 
 * The merge behavior is equivalent to Unix rsync between two drives, where:
 * - Files/directories that exist in both are merged
 * - Files/directories that exist only in one are copied to the result
 * - Conflicts are resolved by timestamp (newer wins)
 */
public class DLFSLattice extends ALattice<AVector<ACell>> {

	/**
	 * Singleton instance of DLFSLattice
	 */
	public static final DLFSLattice INSTANCE = new DLFSLattice();

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

		// Get timestamps from both nodes
		CVMLong timeA = DLFSNode.getUTime(ownValue);
		CVMLong timeB = DLFSNode.getUTime(otherValue);
		
		// Use the maximum timestamp for the merge operation
		// This ensures merged nodes have a timestamp that reflects the most recent change
		CVMLong mergeTime = timeA.longValue() >= timeB.longValue() ? timeA : timeB;

		// Delegate to DLFSNode.merge which implements the rsync-like merge logic
		return DLFSNode.merge(ownValue, otherValue, mergeTime);
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

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		// For DLFS, child paths are directory entry names (AString)
		// Each directory entry points to another DLFS node, which uses the same lattice
		if (childKey instanceof AString) {
			// Return the same lattice type for child nodes (they're also DLFS nodes)
			return (ALattice<T>) this;
		}
		
		// Invalid path key type
		return null;
	}

}
