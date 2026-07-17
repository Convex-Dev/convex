package convex.lattice.fs;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;
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
			return checkForeign(otherValue) ? otherValue : zero();
		}
		if (otherValue == null) {
			return ownValue;
		}

		// Fast path: if values are equal, return own value
		if (Utils.equals(ownValue, otherValue)) {
			return ownValue;
		}

		return safeMerge(ownValue, otherValue);
	}

	@Override
	public AVector<ACell> merge(LatticeContext context, AVector<ACell> ownValue, AVector<ACell> otherValue) {
		// Context timestamp is not used for DLFS merge — the merge is deterministic from
		// the input nodes — so this behaves identically to the no-context overload.
		if (ownValue == null) {
			return checkForeign(otherValue) ? otherValue : zero();
		}
		if (otherValue == null) {
			return ownValue;
		}
		if (Utils.equals(ownValue, otherValue)) {
			return ownValue;
		}
		return safeMerge(ownValue, otherValue);
	}

	/**
	 * Fail-safe merge of two non-null, unequal nodes. {@code other} may originate from an
	 * untrusted peer; rather than pre-validating its structure, the merge is attempted and
	 * falls closed to {@code own} if a malformed node makes it throw. A malformed value can
	 * therefore neither crash the merge (DoS) nor corrupt the merged state — it is ignored.
	 *
	 * <p>#561: this also catches {@link StackOverflowError}. {@code DLFSNode.merge} recurses
	 * through directory nesting, so a maliciously deep node could otherwise overflow the stack
	 * with an {@code Error} that escapes a RuntimeException-only catch. The stack unwinds
	 * cleanly and {@code own} is intact, so falling closed to it is safe.</p>
	 */
	private AVector<ACell> safeMerge(AVector<ACell> own, AVector<ACell> other) {
		if (!checkForeign(other)) return own;
		try {
			return DLFSNode.merge(own, other);
		} catch (RuntimeException | StackOverflowError e) {
			// Malformed / adversarial foreign node (including a maliciously deep one): fail
			// closed and keep own, rather than letting a bad value from an untrusted peer
			// crash or corrupt the merge.
			return own;
		}
	}

	@Override
	public AVector<ACell> zero() {
		// Zero value is an empty directory
		return DLFSNode.createDirectory(CVMLong.ZERO);
	}

	@Override
	public boolean checkForeign(AVector<ACell> value) {
		return DLFSNode.isValidNodeShallow(value);
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
