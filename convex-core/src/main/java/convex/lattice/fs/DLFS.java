package convex.lattice.fs;

import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.store.AStore;
import convex.lattice.cursor.AForkableCursor;
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
	 * Creates a standalone DLFS drive with its own root cursor.
	 * @return A new local DLFS filesystem
	 */
	public static DLFSLocal create() {
		return new DLFSLocal(PROVIDER,null,Cursors.root(DLFSLattice.INSTANCE));
	}

	/**
	 * Connects a named drive under a parent cursor, creating its zero value if
	 * absent. Changes to the drive propagate through to the parent cursor.
	 *
	 * <p>File data remains heap-backed. For incremental blob persistence during
	 * streamed writes use {@link #connect(AForkableCursor, AString, AStore)}.</p>
	 *
	 * @param parent Parent cursor (e.g. an owner's drives map)
	 * @param driveName Name of the drive within the parent
	 * @return A local DLFS filesystem connected to the parent
	 */
	public static DLFSLocal connect(AForkableCursor<?> parent, AString driveName) {
		return connect(parent,driveName,(AStore)null);
	}

	/**
	 * Connects a named drive and checkpoints streamed blob data incrementally in the
	 * supplied store. The caller retains ownership of the store and keeps it open
	 * while the filesystem is in use; it should be the store which publishes the
	 * containing lattice root.
	 *
	 * @param parent Parent cursor
	 * @param driveName Name of the drive within the parent
	 * @param store Store for incremental blob persistence, or null for heap-only data
	 * @return A connected filesystem
	 */
	public static DLFSLocal connect(AForkableCursor<?> parent, AString driveName, AStore store) {
		AForkableCursor<AVector<ACell>> cursor=parent.child(driveName,DLFSLattice.INSTANCE);
		// Atomic initialisation must not replace another caller's concurrent writes.
		cursor.updateAndGet(current -> current!=null?current:DLFSLattice.INSTANCE.zero());
		return (store==null)
			?new DLFSLocal(PROVIDER,driveName.toString(),cursor)
			:DLFSLocal.create(PROVIDER,driveName.toString(),cursor,store);
	}

	/**
	 * Opens an existing named drive without creating it. Intended for registries
	 * which keep the parent map as their source of truth: unlike
	 * {@link #connect(AForkableCursor, AString)}, a concurrent deletion cannot be
	 * reversed merely by opening a cached view. File data remains heap-backed.
	 *
	 * @param parent Parent cursor containing named drives
	 * @param driveName Existing drive name
	 * @return Connected filesystem view, or {@code null} if the drive is absent
	 */
	public static DLFSLocal open(AForkableCursor<?> parent, AString driveName) {
		return open(parent,driveName,(AStore)null);
	}

	/**
	 * Opens an existing drive with incremental blob persistence in the supplied
	 * store. The caller retains ownership of the store.
	 *
	 * @param parent Parent cursor containing named drives
	 * @param driveName Existing drive name
	 * @param store Store for incremental blob persistence, or null for heap-only data
	 * @return Connected filesystem view, or {@code null} if the drive is absent
	 */
	public static DLFSLocal open(AForkableCursor<?> parent, AString driveName, AStore store) {
		AForkableCursor<AVector<ACell>> cursor=parent.child(driveName,DLFSLattice.INSTANCE);
		if (cursor.get()==null) return null;
		return (store==null)
			?new DLFSLocal(PROVIDER,driveName.toString(),cursor)
			:DLFSLocal.create(PROVIDER,driveName.toString(),cursor,store);
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
