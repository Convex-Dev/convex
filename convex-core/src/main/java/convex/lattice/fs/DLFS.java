package convex.lattice.fs;

import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.store.AStore;
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
	 * <p>This overload has no physical persistence policy, so file data remains
	 * heap-backed. Store-backed cursor embedders should use
	 * {@link #connect(ALatticeCursor, AString, AStore)}.</p>
	 *
	 * @param parent Parent lattice cursor (e.g. a signed drives map)
	 * @param driveName Name of the drive within the parent
	 * @return A new local DLFS filesystem connected to the parent
	 */
	public static DLFSLocal connect(ALatticeCursor<?> parent, AString driveName) {
		return connectCursor(parent,driveName,null);
	}

	/**
	 * Creates a DLFS drive connected to a named path and persists streamed blob
	 * data incrementally in the supplied store. The caller retains ownership of
	 * the store and must keep it open while the filesystem is in use.
	 *
	 * <p>The store should be the same store used to publish the containing lattice
	 * root. This overload is intended for cursor-level embedders; component-based
	 * applications normally obtain a store-backed filesystem from
	 * {@code DLFSDrive.fileSystem()}.</p>
	 *
	 * @param parent Parent lattice cursor
	 * @param driveName Name of the drive within the parent
	 * @param store Store used for incremental blob persistence
	 * @return A connected, store-backed filesystem
	 */
	public static DLFSLocal connect(ALatticeCursor<?> parent, AString driveName, AStore store) {
		if (store==null) throw new IllegalArgumentException("DLFS persistence store must not be null");
		return connectCursor(parent,driveName,store);
	}

	private static DLFSLocal connectCursor(ALatticeCursor<?> parent, AString driveName, AStore store) {
		ALatticeCursor<AVector<ACell>> cursor = parent.path(driveName);
		// Atomic init: read-then-set is racy under concurrent connect()s — a late
		// reader could observe null and set(zero), clobbering an earlier writer's
		// committed contents. updateAndGet is a single CAS and is idempotent.
		cursor.updateAndGet(current -> current != null ? current : DLFSLattice.INSTANCE.zero());
		return (store==null)
			?new DLFSLocal(PROVIDER,driveName.toString(),cursor)
			:DLFSLocal.create(PROVIDER,driveName.toString(),cursor,store);
	}

	/**
	 * Opens an existing DLFS drive at a named path without creating it.
	 *
	 * <p>This is intended for registries which keep the parent map as their source of
	 * truth. Unlike {@link #connect(ALatticeCursor, AString)}, a concurrent deletion
	 * cannot be reversed merely by opening a cached filesystem view. This overload
	 * has no physical persistence policy; store-backed cursor embedders should use
	 * {@link #open(ALatticeCursor, AString, AStore)}.</p>
	 *
	 * @param parent Parent lattice cursor containing named drives
	 * @param driveName Existing drive name
	 * @return Connected filesystem view, or {@code null} if the drive is absent
	 */
	public static DLFSLocal open(ALatticeCursor<?> parent, AString driveName) {
		return openCursor(parent,driveName,null);
	}

	/**
	 * Opens an existing cursor-backed drive with incremental blob persistence in
	 * the supplied store. The caller retains ownership of the store.
	 *
	 * @param parent Parent lattice cursor containing named drives
	 * @param driveName Existing drive name
	 * @param store Store used for incremental blob persistence
	 * @return Connected filesystem view, or {@code null} if the drive is absent
	 */
	public static DLFSLocal open(ALatticeCursor<?> parent, AString driveName, AStore store) {
		if (store==null) throw new IllegalArgumentException("DLFS persistence store must not be null");
		return openCursor(parent,driveName,store);
	}

	private static DLFSLocal openCursor(ALatticeCursor<?> parent, AString driveName, AStore store) {
		ALatticeCursor<AVector<ACell>> cursor=parent.path(driveName);
		AVector<ACell> root=cursor.get();
		if (root==null) return null;
		CVMLong timestamp=DLFSNode.getUTime(root);
		return (store==null)
			?new DLFSLocal(PROVIDER,driveName.toString(),cursor,timestamp)
			:DLFSLocal.create(PROVIDER,driveName.toString(),cursor,timestamp,store);
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
