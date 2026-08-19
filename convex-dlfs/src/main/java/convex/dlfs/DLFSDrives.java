package convex.dlfs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.fs.DLFSLattice;
import convex.lattice.fs.DLFSNode;
import convex.lattice.generic.MapLattice;

/** Component representing all named drives belonging to one owner. */
public final class DLFSDrives extends ALatticeComponent<AHashMap<AString, AVector<ACell>>> {
	/** State of a drive registry entry. */
	public enum DriveStatus { LIVE, DELETED, UNTRACKED }

	/** Lattice definition for one owner's map of named DLFS drives. */
	public static final MapLattice<AString, AVector<ACell>> LATTICE=
		MapLattice.create(DLFSLattice.INSTANCE);

	private final ConcurrentHashMap<AString,DLFSDrive> drives=new ConcurrentHashMap<>();

	DLFSDrives(ALatticeComponent<?> parent,
			ALatticeCursor<AHashMap<AString, AVector<ACell>>> cursor) {
		super(parent,cursor);
	}

	/** Creates a standalone, in-memory set of drives for one owner. */
	public static DLFSDrives create() {
		return new DLFSDrives(null,Cursors.createLattice(LATTICE));
	}

	/** Wraps an existing cursor over one owner's drive map. */
	public static DLFSDrives wrap(ALatticeCursor<AHashMap<AString, AVector<ACell>>> cursor) {
		if (cursor==null) throw new IllegalArgumentException("Drives cursor must not be null");
		return new DLFSDrives(null,cursor);
	}

	/** Connects one owner's drives at an arbitrary path below a parent component. */
	public static DLFSDrives connect(ALatticeComponent<?> parent, ACell... path) {
		if (parent==null) throw new IllegalArgumentException("Parent component must not be null");
		ALatticeCursor<AHashMap<AString, AVector<ACell>>> cursor=parent.cursor().path(path);
		return new DLFSDrives(parent,cursor);
	}

	/** Creates an isolated temporary multi-drive working component. */
	public DLFSDrives fork() {
		return new DLFSDrives(parent(),cursor.fork());
	}

	/** Opens an existing drive, or returns null if it is absent. */
	public DLFSDrive drive(String driveName) {
		if (!DLFSPathValidator.isValidDriveName(driveName)) return null;
		AString name=Strings.create(driveName);
		AHashMap<AString,AVector<ACell>> registry=cursor.get();
		if (registry==null || status(registry.get(name))!=DriveStatus.LIVE) return null;
		return drives.compute(name,(key,existing)->{
			if (existing!=null && existing.isOpen()) return existing;
			return new DLFSDrive(this,key,cursor.path(key));
		});
	}

	/** Creates a drive subject to an atomic owner-specific count limit. */
	public DLFSDrive createDrive(String driveName, long maximumDrives) {
		if (!DLFSPathValidator.isValidDriveName(driveName) || maximumDrives<1) return null;
		AString name=Strings.create(driveName);
		CVMLong operationTime=mutationTimestamp();
		AHashMap<AString,AVector<ACell>> previous=cursor.getAndUpdate(registry->{
			if (registry==null) registry=Maps.empty();
			AVector<ACell> existing=registry.get(name);
			DriveStatus status=status(existing);
			if (status==DriveStatus.LIVE) return registry;
			if (existing!=null && status!=DriveStatus.DELETED) return registry;
			if (liveCount(registry)>=maximumDrives) return registry;
			return registry.assoc(name,DLFSNode.createDirectory(operationTime));
		});
		AVector<ACell> old=(previous==null)?null:previous.get(name);
		if (status(old)==DriveStatus.LIVE
				|| (old!=null && status(old)!=DriveStatus.DELETED)
				|| (previous!=null && liveCount(previous)>=maximumDrives)) return null;
		return drive(driveName);
	}

	/** Creates a drive without an application-specific count limit. */
	public DLFSDrive createDrive(String driveName) {
		return createDrive(driveName,Long.MAX_VALUE);
	}

	/** Deletes a drive and closes its cached local NIO view. */
	public boolean deleteDrive(String driveName) {
		if (!DLFSPathValidator.isValidDriveName(driveName)) return false;
		AString name=Strings.create(driveName);
		CVMLong operationTime=mutationTimestamp();
		AHashMap<AString,AVector<ACell>> previous=cursor.getAndUpdate(registry->{
			if (registry==null) return null;
			AVector<ACell> root=registry.get(name);
			if (status(root)!=DriveStatus.LIVE) return registry;
			return registry.assoc(name,DLFSNode.createEmptyFile(operationTime));
		});
		boolean deleted=previous!=null && status(previous.get(name))==DriveStatus.LIVE;
		if (deleted) closeCached(name);
		return deleted;
	}

	/** Atomically renames a drive within this owner's drive map. */
	public boolean renameDrive(String oldName, String newName) {
		if (!DLFSPathValidator.isValidDriveName(oldName)
				|| !DLFSPathValidator.isValidDriveName(newName)) return false;
		if (oldName.equals(newName)) return drive(oldName)!=null;
		AString oldKey=Strings.create(oldName);
		AString newKey=Strings.create(newName);
		CVMLong operationTime=mutationTimestamp();
		AHashMap<AString,AVector<ACell>> previous=cursor.getAndUpdate(registry->{
			if (registry==null) return null;
			AVector<ACell> root=registry.get(oldKey);
			if (status(root)!=DriveStatus.LIVE) return registry;
			AVector<ACell> target=registry.get(newKey);
			DriveStatus targetStatus=status(target);
			if (targetStatus==DriveStatus.LIVE || (target!=null && targetStatus!=DriveStatus.DELETED)) return registry;
			AVector<ACell> movedRoot=root.assoc(DLFSNode.POS_UTIME,operationTime);
			AVector<ACell> tombstone=DLFSNode.createEmptyFile(operationTime);
			return registry.assoc(newKey,movedRoot).assoc(oldKey,tombstone);
		});
		if (previous==null || status(previous.get(oldKey))!=DriveStatus.LIVE) return false;
		AVector<ACell> oldTarget=previous.get(newKey);
		DriveStatus targetStatus=status(oldTarget);
		if (targetStatus==DriveStatus.LIVE || (oldTarget!=null && targetStatus!=DriveStatus.DELETED)) return false;
		closeCached(oldKey);
		closeCached(newKey);
		return true;
	}

	/** Returns sorted drive names belonging to this owner. */
	public List<String> driveNames() {
		AHashMap<AString,AVector<ACell>> registry=cursor.get();
		if (registry==null || registry.isEmpty()) return List.of();
		List<String> result=new ArrayList<>((int)Math.min(Integer.MAX_VALUE,registry.count()));
		for (AString name: registry.keySet()) {
			if (status(registry.get(name))==DriveStatus.LIVE) result.add(name.toString());
		}
		result.sort(String::compareTo);
		return result;
	}

	/** Returns whether a name is live, deleted, or has never been tracked. */
	public DriveStatus driveStatus(String driveName) {
		if (!DLFSPathValidator.isValidDriveName(driveName)) return DriveStatus.UNTRACKED;
		AHashMap<AString,AVector<ACell>> registry=cursor.get();
		return status((registry==null)?null:registry.get(Strings.create(driveName)));
	}

	private static DriveStatus status(AVector<ACell> root) {
		if (root==null) return DriveStatus.UNTRACKED;
		if (!DLFSNode.isValidNodeShallow(root)) return DriveStatus.UNTRACKED;
		if (DLFSNode.isDirectory(root)) return DriveStatus.LIVE;
		if (DLFSNode.isRegularFile(root)
				&& DLFSNode.getData(root).count()==0
				&& DLFSNode.getMetadata(root)==null) return DriveStatus.DELETED;
		return DriveStatus.UNTRACKED;
	}

	private static long liveCount(AHashMap<AString,AVector<ACell>> registry) {
		long count=0;
		for (AString name:registry.keySet()) {
			if (status(registry.get(name))==DriveStatus.LIVE) count++;
		}
		return count;
	}

	/**
	 * Returns the caller-controlled write timestamp without modification.
	 * Ordering distinct mutations is the responsibility of the application which
	 * owns the lattice context.
	 */
	private CVMLong mutationTimestamp() {
		return cursor.getContext().currentTimestamp();
	}

	private void closeCached(AString name) {
		DLFSDrive drive=drives.remove(name);
		if (drive==null) return;
		try {
			drive.close();
		} catch (IOException e) {
			// DLFS views own no external resources; the lattice mutation succeeded.
		}
	}
}
