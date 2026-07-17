package convex.dlfs;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.fs.DLFS;
import convex.lattice.fs.DLFSLattice;

/**
 * Manages named DLFS drives per user identity.
 *
 * <p>Each drive is identified by the combination of an owner identity (DID string
 * from JWT authentication) and a drive name. Drives are created on demand via
 * {@link #createDrive} and resolved by name via {@link #getDrive}.
 *
 * <p>The no-argument form uses in-memory {@code DLFS.createLocal()} instances.
 * The cursor-backed form treats a map of drive names to DLFS roots as the
 * authoritative registry and keeps {@link FileSystem} objects only as local views.
 */
public class DLFSDriveManager {
	/** Conservative per-identity bound for the in-process drive registry. */
	public static final int MAX_DRIVES_PER_IDENTITY = 256;

	private final ConcurrentHashMap<String, FileSystem> drives = new ConcurrentHashMap<>();
	private final ALatticeCursor<AHashMap<AString, AVector<ACell>>> drivesCursor;

	/** Creates a standalone in-memory drive registry. */
	public DLFSDriveManager() {
		this.drivesCursor=null;
	}

	/**
	 * Creates a cursor-backed registry for one anonymous/local owner namespace.
	 * Authenticated multi-owner routing requires a distinct manager at each owner's
	 * drives cursor and is intentionally not inferred here.
	 *
	 * @param drivesCursor Cursor over a {@code MapLattice<AString, DLFSLattice>}
	 */
	public DLFSDriveManager(ALatticeCursor<AHashMap<AString, AVector<ACell>>> drivesCursor) {
		this.drivesCursor=Objects.requireNonNull(drivesCursor);
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
		if (drivesCursor!=null) return getCursorDrive(identity, driveName);
		return drives.get(driveKey(identity, driveName));
	}

	private FileSystem getCursorDrive(String identity, String driveName) {
		if (identity!=null) return null;
		AString name=Strings.create(driveName);
		AHashMap<AString, AVector<ACell>> registry=drivesCursor.get();
		if (registry==null || registry.get(name)==null) return null;
		String key=driveKey(null, driveName);
		FileSystem existing=drives.get(key);
		if (existing!=null && existing.isOpen()) return existing;
		FileSystem opened=DLFS.open(drivesCursor, name);
		if (opened==null) return null; // concurrently removed
		drives.put(key, opened);
		return opened;
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
		if (drivesCursor!=null) return createCursorDrive(identity, driveName);
		String key = driveKey(identity, driveName);
		if (listDrives(identity).size() >= MAX_DRIVES_PER_IDENTITY) return false;
		FileSystem existing = drives.putIfAbsent(key, DLFS.createLocal());
		return existing == null;
	}

	private boolean createCursorDrive(String identity, String driveName) {
		if (identity!=null) return false;
		AString name=Strings.create(driveName);
		AHashMap<AString, AVector<ACell>> previous=drivesCursor.getAndUpdate(registry->{
			if (registry==null) registry=Maps.empty();
			if (registry.containsKey(name) || registry.count()>=MAX_DRIVES_PER_IDENTITY) return registry;
			return registry.assoc(name, DLFSLattice.INSTANCE.zero());
		});
		return (previous==null || !previous.containsKey(name))
			&& (previous==null || previous.count()<MAX_DRIVES_PER_IDENTITY);
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
		if (drivesCursor!=null) return deleteCursorDrive(identity, driveName);
		return drives.remove(driveKey(identity, driveName)) != null;
	}

	private boolean deleteCursorDrive(String identity, String driveName) {
		if (identity!=null) return false;
		AString name=Strings.create(driveName);
		AHashMap<AString, AVector<ACell>> previous=drivesCursor.getAndUpdate(registry->{
			if (registry==null || !registry.containsKey(name)) return registry;
			return registry.dissoc(name);
		});
		boolean deleted=previous!=null && previous.containsKey(name);
		if (deleted) closeCached(driveKey(null, driveName));
		return deleted;
	}

	/**
	 * Lists drive names for the given identity.
	 *
	 * @param identity Owner identity (DID string), or null for anonymous
	 * @return List of drive names owned by this identity
	 */
	public List<String> listDrives(String identity) {
		if (drivesCursor!=null) return listCursorDrives(identity);
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

	private List<String> listCursorDrives(String identity) {
		if (identity!=null) return List.of();
		AHashMap<AString, AVector<ACell>> registry=drivesCursor.get();
		if (registry==null || registry.isEmpty()) return List.of();
		List<String> result=new ArrayList<>((int)Math.min(Integer.MAX_VALUE, registry.count()));
		for (AString name: registry.keySet()) result.add(name.toString());
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
		if (drivesCursor!=null) return renameCursorDrive(identity, oldName, newName);
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

	private boolean renameCursorDrive(String identity, String oldName, String newName) {
		if (identity!=null) return false;
		if (oldName.equals(newName)) return getDrive(null, oldName)!=null;
		AString oldKey=Strings.create(oldName);
		AString newKey=Strings.create(newName);
		AHashMap<AString, AVector<ACell>> previous=drivesCursor.getAndUpdate(registry->{
			if (registry==null || registry.containsKey(newKey)) return registry;
			AVector<ACell> root=registry.get(oldKey);
			if (root==null) return registry;
			return registry.assoc(newKey, root).dissoc(oldKey);
		});
		if (previous==null || previous.get(oldKey)==null || previous.containsKey(newKey)) return false;
		closeCached(driveKey(null, oldName));
		closeCached(driveKey(null, newName));
		return true;
	}

	/**
	 * Synchronises cursor-backed registry changes to the cursor's parent or root
	 * callback. Standalone registries have no persistence boundary and do nothing.
	 */
	public void sync() {
		if (drivesCursor!=null) drivesCursor.sync();
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
		if (drivesCursor!=null) {
			throw new UnsupportedOperationException("Cannot seed a cursor-backed drive registry with a detached filesystem");
		}
		drives.put(driveKey(identity, driveName), fs);
	}

	private void closeCached(String key) {
		FileSystem fs=drives.remove(key);
		if (fs==null) return;
		try {
			fs.close();
		} catch (IOException e) {
			// DLFS views own no external resources; registry mutation has already succeeded.
		}
	}

	private static String driveKey(String identity, String driveName) {
		return identityPrefix(identity) + driveName;
	}

	private static String identityPrefix(String identity) {
		if (identity == null) return ":";
		return identity + ":";
	}
}
