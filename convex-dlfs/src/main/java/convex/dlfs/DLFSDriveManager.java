package convex.dlfs;

import java.nio.file.FileSystem;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import convex.lattice.fs.DLFS;

/**
 * Manages named DLFS drives per user identity.
 *
 * <p>Each drive is identified by the combination of an owner identity (DID string
 * from JWT authentication) and a drive name. Drives are created on demand via
 * {@link #createDrive} and resolved by name via {@link #getDrive}.
 *
 * <p>This implementation uses in-memory {@code DLFS.createLocal()} instances.
 * It can be replaced with a lattice cursor-backed implementation for
 * replication support.
 */
public class DLFSDriveManager {

	private final ConcurrentHashMap<String, FileSystem> drives = new ConcurrentHashMap<>();

	/**
	 * Gets an existing drive for the given identity and name.
	 *
	 * @param identity Owner identity (DID string), or null for anonymous
	 * @param driveName Drive name
	 * @return The FileSystem for the drive, or null if it doesn't exist
	 */
	public FileSystem getDrive(String identity, String driveName) {
		return drives.get(driveKey(identity, driveName));
	}

	/**
	 * Creates a new drive for the given identity and name.
	 *
	 * @param identity Owner identity (DID string)
	 * @param driveName Drive name
	 * @return true if created, false if drive already exists
	 */
	public boolean createDrive(String identity, String driveName) {
		String key = driveKey(identity, driveName);
		FileSystem existing = drives.putIfAbsent(key, DLFS.createLocal());
		return existing == null;
	}

	/**
	 * Deletes a drive. Only succeeds if the drive exists.
	 *
	 * @param identity Owner identity (DID string)
	 * @param driveName Drive name
	 * @return true if deleted, false if drive didn't exist
	 */
	public boolean deleteDrive(String identity, String driveName) {
		return drives.remove(driveKey(identity, driveName)) != null;
	}

	/**
	 * Lists drive names for the given identity.
	 *
	 * @param identity Owner identity (DID string), or null for anonymous
	 * @return List of drive names owned by this identity
	 */
	public List<String> listDrives(String identity) {
		String prefix = identityPrefix(identity);
		List<String> result = new ArrayList<>();
		for (String key : drives.keySet()) {
			if (key.startsWith(prefix)) {
				result.add(key.substring(prefix.length()));
			}
		}
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
	public boolean renameDrive(String identity, String oldName, String newName) {
		String oldKey = driveKey(identity, oldName);
		String newKey = driveKey(identity, newName);
		FileSystem fs = drives.get(oldKey);
		if (fs == null) return false;
		// Atomically check newKey doesn't exist and insert
		if (drives.putIfAbsent(newKey, fs) != null) return false;
		drives.remove(oldKey);
		return true;
	}

	/**
	 * Seeds a drive with a pre-existing filesystem (e.g. for testing or demo).
	 *
	 * @param identity Owner identity (DID string), or null for anonymous
	 * @param driveName Drive name
	 * @param fs The filesystem to use for this drive
	 */
	public void seedDrive(String identity, String driveName, FileSystem fs) {
		drives.put(driveKey(identity, driveName), fs);
	}

	private static String driveKey(String identity, String driveName) {
		return identityPrefix(identity) + driveName;
	}

	private static String identityPrefix(String identity) {
		if (identity == null) return ":";
		return identity + ":";
	}
}
