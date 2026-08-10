package convex.lattice.fs;

import java.nio.file.CopyOption;

/**
 * DLFS-specific options for copy and move operations.
 */
public enum DLFSOption implements CopyOption {

	/**
	 * Copies or moves a complete directory subtree in one atomic drive update.
	 * Recursive copies structurally share the immutable descendant nodes.
	 */
	RECURSIVE
}
