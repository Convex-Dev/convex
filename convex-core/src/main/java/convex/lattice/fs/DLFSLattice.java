package convex.lattice.fs;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.util.MergeFunction;
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
		if (key instanceof AInteger) return key;
		if (key instanceof AString) {
			// Try parsing as integer for vector position access
			AInteger n = AInteger.parse(key);
			if (n != null) return n;
			return key; // String filename
		}
		return key;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		// Handle numeric index (0 = directory entries, POS_DIR)
		if (childKey instanceof AInteger) {
			long idx = ((AInteger) childKey).longValue();
			if (idx == DLFSNode.POS_DIR) {
				// Return a lattice for directory entries (Index<AString, AVector<ACell>>)
				// This lattice will return DLFSLattice when path(AString) is called
				return (ALattice<T>) DirectoryEntriesLattice.INSTANCE;
			}
			// Other indices (1=data, 2=metadata, 3=utime) don't have child lattices
			return null;
		}
		
		// For DLFS, child paths can also be directory entry names (AString)
		// This handles direct access when the path doesn't start with [0]
		// Each directory entry points to another DLFS node, which uses the same lattice
		if (childKey instanceof AString) {
			// Return the same lattice type for child nodes (they're also DLFS nodes)
			return (ALattice<T>) this;
		}
		
		// Invalid path key type
		return null;
	}

	/**
	 * Lattice for directory entries (Index<AString, AVector<ACell>>).
	 * When path(AString) is called, returns DLFSLattice for the child node.
	 */
	private static class DirectoryEntriesLattice extends ALattice<Index<AString, AVector<ACell>>> {
		static final DirectoryEntriesLattice INSTANCE = new DirectoryEntriesLattice();
		
		private DirectoryEntriesLattice() {
			// Private constructor for singleton
		}
		
		@Override
		public Index<AString, AVector<ACell>> merge(Index<AString, AVector<ACell>> ownValue, Index<AString, AVector<ACell>> otherValue) {
			if (ownValue == null) {
				if (otherValue == null) return zero();
				return otherValue;
			}
			if (otherValue == null) {
				return ownValue;
			}

			// Merge directory entries using DLFSLattice for values
			MergeFunction<AVector<ACell>> mergeFunction = (a, b) -> {
				return DLFSLattice.INSTANCE.merge(a, b);
			};

			return ownValue.mergeDifferences(otherValue, mergeFunction);
		}

		@Override
		public Index<AString, AVector<ACell>> merge(convex.lattice.LatticeContext context, Index<AString, AVector<ACell>> ownValue, Index<AString, AVector<ACell>> otherValue) {
			if (ownValue == null) {
				if (otherValue == null) return zero();
				return otherValue;
			}
			if (otherValue == null) {
				return ownValue;
			}

			// Merge directory entries using context-aware DLFSLattice for values
			MergeFunction<AVector<ACell>> mergeFunction = (a, b) -> {
				return DLFSLattice.INSTANCE.merge(context, a, b);
			};

			return ownValue.mergeDifferences(otherValue, mergeFunction);
		}
		
		@Override
		public Index<AString, AVector<ACell>> zero() {
			return Index.none();
		}
		
		@Override
		public boolean checkForeign(Index<AString, AVector<ACell>> value) {
			return (value instanceof Index);
		}
		
		@SuppressWarnings("unchecked")
		@Override
		public <T extends ACell> ALattice<T> path(ACell childKey) {
			// Directory entry names (AString) point to DLFS nodes
			if (childKey instanceof AString) {
				return (ALattice<T>) DLFSLattice.INSTANCE;
			}
			return null;
		}
	}

}
