package convex.dlfs;

import java.nio.file.FileSystem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.fs.DLFS;
import convex.lattice.fs.impl.DLFSLocal;

/**
 * Local routing and service adapter for named DLFS drives.
 *
 * <p>Each drive is identified by the combination of an owner identity (DID string
 * from JWT authentication) and a drive name. Drives are created on demand via
 * {@link #createDrive} and resolved by name via {@link #getDrive}.
 *
 * <p>{@link #createRouter()} fails closed for unmounted identities, while
 * {@link #createEphemeral()} maps identities and names to in-memory
 * {@code DLFS.createLocal()} instances. Applications may
 * {@link #mount(String, DLFSDrives) mount} identities onto owner components from
 * any physical region or hosted root. FileSystem objects remain local views;
 * only component values enter the lattice.
 */
public class DLFSDriveManager {
	/** Conservative per-identity bound for the in-process drive registry. */
	public static final int MAX_DRIVES_PER_IDENTITY = 256;
	private static final Object ANONYMOUS_IDENTITY=new Object();

	private final ConcurrentHashMap<String, FileSystem> drives = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Object,DLFSDrives> componentRoutes=new ConcurrentHashMap<>();
	private final boolean ephemeralFallback;

	/** Refreshes the application-owned timestamp context for one service mutation. */
	static void prepareMutation(FileSystem fs) {
		if (!(fs instanceof DLFSLocal dlfs)) return;
		ALatticeCursor<AVector<ACell>> cursor=dlfs.getCursor();
		CVMLong timestamp=CVMLong.create(Utils.getCurrentTimestamp());
		cursor.setContext(cursor.getContext().withTimestamp(timestamp));
	}

	private DLFSDriveManager(boolean ephemeralFallback) {
		this.ephemeralFallback=ephemeralFallback;
	}

	/**
	 * Creates an explicit component router. Unmounted identities expose no drives
	 * and cannot mutate process-local state.
	 *
	 * @return Empty routing manager
	 */
	public static DLFSDriveManager createRouter() {
		return new DLFSDriveManager(false);
	}

	/**
	 * Creates a standalone in-memory drive registry for tests and demonstrations.
	 *
	 * @return Ephemeral drive manager
	 */
	public static DLFSDriveManager createEphemeral() {
		return new DLFSDriveManager(true);
	}

	/**
	 * Creates a manager with one cursor-backed drive collection mounted for
	 * anonymous requests. Additional identities may be mounted independently.
	 *
	 * @param drivesCursor Cursor over a {@code MapLattice<AString, DLFSLattice>}
	 */
	public DLFSDriveManager(ALatticeCursor<AHashMap<AString, AVector<ACell>>> drivesCursor) {
		this(DLFSDrives.wrap(drivesCursor));
	}

	/**
	 * Creates a manager with one owner's component-backed drives mounted for
	 * anonymous requests. Blob persistence delegates through the component's
	 * physical region, application and root.
	 *
	 * @param drives Component representing this owner's drives
	 */
	public DLFSDriveManager(DLFSDrives drives) {
		this(false);
		mount(null,drives);
	}

	/**
	 * Mounts an external identity onto one owner-scoped drive component.
	 *
	 * <p>The identity is local service policy and need not equal the lattice owner
	 * key. A null identity maps anonymous requests. Mounting the same component
	 * repeatedly is idempotent; remapping an existing identity to another component
	 * fails explicitly.</p>
	 *
	 * @param identity External identity, or null for anonymous requests
	 * @param drives Owner-scoped component to route to
	 * @return This manager
	 */
	public DLFSDriveManager mount(String identity, DLFSDrives drives) {
		if (drives==null) throw new IllegalArgumentException("DLFS drives must not be null");
		Object key=identityKey(identity);
		DLFSDrives existing=componentRoutes.putIfAbsent(key,drives);
		if (existing!=null && existing!=drives) {
			throw new IllegalStateException("DLFS identity is already mounted: "+identityLabel(identity));
		}
		return this;
	}

	/**
	 * Mounts anonymous requests onto one owner-scoped drive component.
	 *
	 * @param drives Owner-scoped component to expose anonymously
	 * @return This manager
	 */
	public DLFSDriveManager mountAnonymous(DLFSDrives drives) {
		return mount(null,drives);
	}

	/**
	 * Checks whether a drive name is safe and representable at service boundaries.
	 *
	 * @param driveName Candidate drive name
	 * @return true if valid
	 */
	public static boolean isValidDriveName(String driveName) {
		return DLFSPathValidator.isValidDriveName(driveName);
	}

	/**
	 * Gets an existing drive for the given identity and name.
	 *
	 * @param identity Owner identity (DID string), or null for anonymous
	 * @param driveName Drive name
	 * @return The FileSystem for the drive, or null if it doesn't exist
	 */
	public FileSystem getDrive(String identity, String driveName) {
		if (!DLFSPathValidator.isValidDriveName(driveName)) return null;
		DLFSDrives componentDrives=componentRoutes.get(identityKey(identity));
		if (componentDrives!=null) return getCursorDrive(componentDrives,driveName);
		if (!ephemeralFallback) return null;
		return drives.get(driveKey(identity, driveName));
	}

	private FileSystem getCursorDrive(DLFSDrives componentDrives, String driveName) {
		DLFSDrive drive=componentDrives.drive(driveName);
		return (drive==null)?null:drive.fileSystem();
	}

	/**
	 * Creates a new drive for the given identity and name.
	 *
	 * @param identity Owner identity (DID string)
	 * @param driveName Drive name
	 * @return true if created, false if drive already exists
	 */
	public synchronized boolean createDrive(String identity, String driveName) {
		if (!DLFSPathValidator.isValidDriveName(driveName)) return false;
		DLFSDrives componentDrives=componentRoutes.get(identityKey(identity));
		if (componentDrives!=null) return createCursorDrive(componentDrives,driveName);
		if (!ephemeralFallback) return false;
		String key = driveKey(identity, driveName);
		if (listDrives(identity).size() >= MAX_DRIVES_PER_IDENTITY) return false;
		FileSystem existing = drives.putIfAbsent(key, DLFS.createLocal());
		return existing == null;
	}

	private boolean createCursorDrive(DLFSDrives componentDrives, String driveName) {
		return componentDrives.createDrive(driveName,MAX_DRIVES_PER_IDENTITY)!=null;
	}

	/**
	 * Deletes a drive. Only succeeds if the drive exists.
	 *
	 * @param identity Owner identity (DID string)
	 * @param driveName Drive name
	 * @return true if deleted, false if drive didn't exist
	 */
	public synchronized boolean deleteDrive(String identity, String driveName) {
		if (!DLFSPathValidator.isValidDriveName(driveName)) return false;
		DLFSDrives componentDrives=componentRoutes.get(identityKey(identity));
		if (componentDrives!=null) return deleteCursorDrive(componentDrives,driveName);
		if (!ephemeralFallback) return false;
		return drives.remove(driveKey(identity, driveName)) != null;
	}

	private boolean deleteCursorDrive(DLFSDrives componentDrives, String driveName) {
		return componentDrives.deleteDrive(driveName);
	}

	/**
	 * Lists drive names for the given identity.
	 *
	 * @param identity Owner identity (DID string), or null for anonymous
	 * @return List of drive names owned by this identity
	 */
	public List<String> listDrives(String identity) {
		DLFSDrives componentDrives=componentRoutes.get(identityKey(identity));
		if (componentDrives!=null) return componentDrives.driveNames();
		if (!ephemeralFallback) return List.of();
		String prefix = identityPrefix(identity);
		List<String> result = new ArrayList<>();
		for (String key : drives.keySet()) {
			if (key.startsWith(prefix)) {
				result.add(key.substring(prefix.length()));
			}
		}
		result.sort(String::compareTo);
		return result;
	}

	/**
	 * Renames a drive. Atomically removes the old name and adds the new name.
	 *
	 * @param identity Owner identity (DID string), or null for anonymous
	 * @param oldName Current drive name
	 * @param newName New drive name
	 * @return true if renamed, false if source doesn't exist or target already exists
	 */
	public synchronized boolean renameDrive(String identity, String oldName, String newName) {
		if (!DLFSPathValidator.isValidDriveName(oldName) || !DLFSPathValidator.isValidDriveName(newName)) return false;
		DLFSDrives componentDrives=componentRoutes.get(identityKey(identity));
		if (componentDrives!=null) return renameCursorDrive(componentDrives,oldName,newName);
		if (!ephemeralFallback) return false;
		if (oldName.equals(newName)) return getDrive(identity, oldName) != null;
		String oldKey = driveKey(identity, oldName);
		String newKey = driveKey(identity, newName);
		FileSystem fs = drives.get(oldKey);
		if (fs == null) return false;
		// The method lock makes the two map mutations one registry operation.
		if (drives.putIfAbsent(newKey, fs) != null) return false;
		if (!drives.remove(oldKey, fs)) {
			drives.remove(newKey, fs);
			return false;
		}
		return true;
	}

	private boolean renameCursorDrive(DLFSDrives componentDrives, String oldName, String newName) {
		return componentDrives.renameDrive(oldName,newName);
	}

	/**
	 * Synchronises every mounted component to its parent or hosted root. Detached
	 * identities have no persistence boundary and do nothing.
	 */
	public void sync() {
		for (DLFSDrives mounted:new HashSet<>(componentRoutes.values())) mounted.sync();
	}

	/**
	 * Synchronises only the component mounted for one identity.
	 *
	 * @param identity External identity, or null for anonymous requests
	 */
	public void sync(String identity) {
		DLFSDrives mounted=componentRoutes.get(identityKey(identity));
		if (mounted!=null) mounted.sync();
	}

	/**
	 * Seeds a drive with a pre-existing filesystem (e.g. for testing or demo).
	 *
	 * @param identity Owner identity (DID string), or null for anonymous
	 * @param driveName Drive name
	 * @param fs The filesystem to use for this drive
	 */
	public synchronized void seedDrive(String identity, String driveName, FileSystem fs) {
		if (!DLFSPathValidator.isValidDriveName(driveName)) {
			throw new IllegalArgumentException("Invalid drive name: " + driveName);
		}
		if (fs == null) throw new IllegalArgumentException("Drive filesystem cannot be null");
		if (componentRoutes.containsKey(identityKey(identity))) {
			throw new UnsupportedOperationException("Cannot seed a mounted identity with a detached filesystem");
		}
		if (!ephemeralFallback) {
			throw new UnsupportedOperationException("Cannot seed an unmounted identity on a routing manager");
		}
		drives.put(driveKey(identity, driveName), fs);
	}

	private static String driveKey(String identity, String driveName) {
		return identityPrefix(identity) + driveName;
	}

	private static String identityPrefix(String identity) {
		if (identity == null) return ":";
		return identity + ":";
	}

	private static Object identityKey(String identity) {
		return (identity==null)?ANONYMOUS_IDENTITY:identity;
	}

	private static String identityLabel(String identity) {
		return (identity==null)?"<anonymous>":identity;
	}
}
