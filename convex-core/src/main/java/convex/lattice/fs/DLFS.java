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
		return new DLFSLocal(PROVIDER,null,Cursors.createLattice(DLFSLattice.INSTANCE));
	}

	/**
	 * Connects a named drive, creating its zero value if absent. Persistence is
	 * provided by the parent cursor's host; unhosted cursors remain in memory.
	 */
	public static DLFSLocal connect(ALatticeCursor<?> parent,AString driveName) {
		ALatticeCursor<AVector<ACell>> cursor=parent.path(driveName);
		// Atomic initialisation must not replace another caller's concurrent writes.
		cursor.updateAndGet(current -> current!=null?current:DLFSLattice.INSTANCE.zero());
		return new DLFSLocal(PROVIDER,driveName.toString(),cursor);
	}

	/**
	 * Opens an existing named drive without creating it. Persistence follows the
	 * parent cursor's host. Returns null when the drive is absent.
	 */
	public static DLFSLocal open(ALatticeCursor<?> parent,AString driveName) {
		ALatticeCursor<AVector<ACell>> cursor=parent.path(driveName);
		if (cursor.get()==null) return null;
		return new DLFSLocal(PROVIDER,driveName.toString(),cursor);
	}

	public static DLFSLocal createLocal() {
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
