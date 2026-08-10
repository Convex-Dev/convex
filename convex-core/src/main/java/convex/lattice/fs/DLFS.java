package convex.lattice.fs;

import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.fs.impl.DLFSLocal;

/**
 * Static API for DLFS
 */
public class DLFS {
	/** Maximum UTF-8 byte length of one path component. */
	public static final int MAX_NAME_LENGTH=255;

	private static final DLFSProvider PROVIDER=new DLFSProvider();
	
	/**
	 * URI scheme for DLFS
	 */
	public static final String SCHEME = "dlfs";

	public static final String ROOT_STRING = "/";
	
	public static DLFSProvider provider() {
		return PROVIDER;
	}
	
	/**
	 * Creates a standalone DLFS drive with its own root lattice cursor.
	 * @return A new local DLFS filesystem
	 */
	public static DLFSLocal create() {
		return new DLFSLocal(PROVIDER, null,
			Cursors.createLattice(DLFSLattice.INSTANCE));
	}

	/**
	 * Creates a DLFS drive connected to a named path under a parent lattice cursor.
	 * Changes to the drive propagate through to the parent cursor.
	 *
	 * @param parent Parent lattice cursor (e.g. a signed drives map)
	 * @param driveName Name of the drive within the parent
	 * @return A new local DLFS filesystem connected to the parent
	 */
	public static DLFSLocal connect(ALatticeCursor<?> parent, AString driveName) {
		ALatticeCursor<AVector<ACell>> cursor = parent.path(driveName);
		// Atomic init: read-then-set is racy under concurrent connect()s — a late
		// reader could observe null and set(zero), clobbering an earlier writer's
		// committed contents. updateAndGet is a single CAS and is idempotent.
		cursor.updateAndGet(current -> current != null ? current : DLFSLattice.INSTANCE.zero());
		return new DLFSLocal(PROVIDER, driveName.toString(), cursor);
	}

	/**
	 * Opens an existing DLFS drive at a named path without creating it.
	 *
	 * <p>This is intended for registries which keep the parent map as their source of
	 * truth. Unlike {@link #connect(ALatticeCursor, AString)}, a concurrent deletion
	 * cannot be reversed merely by opening a cached filesystem view.</p>
	 *
	 * @param parent Parent lattice cursor containing named drives
	 * @param driveName Existing drive name
	 * @return Connected filesystem view, or {@code null} if the drive is absent
	 */
	public static DLFSLocal open(ALatticeCursor<?> parent, AString driveName) {
		ALatticeCursor<AVector<ACell>> cursor=parent.path(driveName);
		AVector<ACell> root=cursor.get();
		if (root==null) return null;
		return new DLFSLocal(PROVIDER, driveName.toString(), cursor, DLFSNode.getUTime(root));
	}

	public static DLFileSystem createLocal() {
		return create();
	}

	/**
	 * Converts to a DLFS path
	 * @param path Path to check
	 * @return DLFS compatible Path instance
	 * @throws ProviderMismatchException if not a DLFS file
	 */
	public static DLPath checkPath(Path path) {
		if (path instanceof DLPath) return (DLPath) path;
		throw new ProviderMismatchException("Not a DLFS path");
	}

	public static AString checkName(String name) {
		if (name==null) return null;
		if (name.isEmpty()) return null;
		return checkName(Strings.create(name));
	}

	public static AString checkName(AString name) {
		if (name==null) return null;
		if (name.isEmpty()) return null;
		if (name.count()>MAX_NAME_LENGTH) return null;
		return name;
	}

}
