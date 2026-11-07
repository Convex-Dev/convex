package convex.lattice.fs;

import java.nio.file.FileSystemException;

/**
 * Thrown when a conditional DLFS write finds its condition false for the file's
 * current entry. The drive is left unchanged.
 */
@SuppressWarnings("serial")
public class ConditionFailedException extends FileSystemException {

	public ConditionFailedException(String file) {
		super(file,null,"Condition not met");
	}
}
