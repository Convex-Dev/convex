package convex.lattice.fs;

import java.nio.file.FileSystemException;

/**
 * Signals that stored DLFS state is malformed in a way that prevents the
 * requested filesystem operation from being completed safely.
 */
public class DLFSCorruptionException extends FileSystemException {

	private static final long serialVersionUID = 1L;

	public DLFSCorruptionException(String path, String reason) {
		super(path, null, reason);
	}
}
