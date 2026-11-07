package convex.lattice.fs;

import java.util.Objects;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.Hash;

/**
 * Condition on a file's current entry, checked inside the atomic update that
 * installs new content. The entry is null when the file does not exist.
 *
 * A condition must be cheap and free of side effects: under contention it is
 * checked again on each attempt.
 */
@FunctionalInterface
public interface DLFSCondition {

	/**
	 * Tests the file's current entry
	 * @param entry Current entry, or null if the file does not exist
	 * @return true if the write may proceed
	 */
	boolean test(AVector<ACell> entry);

	/** Replaces whatever is there */
	DLFSCondition ANY=entry -> true;

	/** Creates the file only if it does not exist */
	DLFSCondition ABSENT=entry -> entry==null;

	/**
	 * Requires the entry to be unchanged since it was read. The entry hash covers
	 * metadata and times as well as content, and is what WebDAV serves as the ETag.
	 * @param entryHash Hash of the entry as read
	 * @return Condition that holds only for that entry
	 */
	static DLFSCondition matching(Hash entryHash) {
		Objects.requireNonNull(entryHash);
		return entry -> (entry!=null) && entryHash.equals(Cells.getHash(entry));
	}
}
