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
		if (cursor.get() == null) {
			cursor.set(DLFSLattice.INSTANCE.zero());
		}
		return new DLFSLocal(PROVIDER, driveName.toString(), cursor);
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
		return Strings.create(name);
	}

	public static AString checkName(AString name) {
		if (name==null) return null;
		if (name.isEmpty()) return null;
		return name;
	}

}
