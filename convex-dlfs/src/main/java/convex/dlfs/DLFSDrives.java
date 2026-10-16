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
import convex.core.store.AStore;
import convex.lattice.ALatticeComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.fs.DLFSLattice;
import convex.lattice.fs.DLFSLocation;
import convex.lattice.fs.DLFSNode;
import convex.lattice.generic.MapLattice;

/**
 * Component representing all named drives belonging to one owner.
 *
 * <p>The optional store is handed to each drive for incremental blob checkpoints
 * during streamed writes; it is not used for anything else.</p>
 */
public final class DLFSDrives extends ALatticeComponent<AHashMap<AString, AVector<ACell>>> implements TransactionalComponent<AHashMap<AString, AVector<ACell>>,DLFSDrives> {
	/** State of a drive registry entry. */
	public enum DriveStatus { LIVE, DELETED, UNTRACKED }

	/** Lattice definition for one owner's map of named DLFS drives. */
	public static final MapLattice<AString, AVector<ACell>> LATTICE=
		MapLattice.create(DLFSLattice.INSTANCE);

	private final AStore store;
	private final DLFSLocation location;
	private final ConcurrentHashMap<AString,DLFSDrive> drives=new ConcurrentHashMap<>();

	DLFSDrives(AForkableCursor<AHashMap<AString, AVector<ACell>>> cursor, AStore store) {
		this(cursor,store,DLFSLocation.at(cursor));
	}

	DLFSDrives(AForkableCursor<AHashMap<AString, AVector<ACell>>> cursor,AStore store,DLFSLocation location) {
		super(cursor);
		this.store=store;
		this.location=location;
	}

	/** Creates a standalone, in-memory set of drives for one owner. */
	public static DLFSDrives create() {
		return new DLFSDrives(Cursors.root(LATTICE.zero()),null);
	}

	/** Wraps an existing cursor over one owner's drive map; file data stays heap-backed. */
	public static DLFSDrives wrap(AForkableCursor<AHashMap<AString, AVector<ACell>>> cursor) {
		if (cursor==null) throw new IllegalArgumentException("Drives cursor must not be null");
		return new DLFSDrives(cursor,null);
	}

	/**
	 * Connects one owner's drive map at the position of a cursor, checkpointing
	 * streamed blob data into the supplied store. The owning component hands the
	 * cursor down; set the context explicitly when the owner's policy should apply.
	 *
	 * @param cursor Cursor at the drive map
	 * @param store Store for incremental blob checkpoints, or null for heap-only file data
	 * @return Drives component
	 */
	public static DLFSDrives connect(AForkableCursor<AHashMap<AString, AVector<ACell>>> cursor,AStore store) {
		if (cursor==null) throw new IllegalArgumentException("Drives cursor must not be null");
		return new DLFSDrives(cursor,store);
	}

	/** Creates an isolated temporary multi-drive working component. */
	public DLFSDrives fork() {
		return configured(new DLFSDrives(this));
	}

	private DLFSDrives(DLFSDrives source) {
		super(LATTICE,source.cursor);
		this.store=source.store;
		this.location=DLFSLocation.at(cursor);
	}

	/** Opens an existing drive, or returns null if it is absent. */
	public DLFSDrive drive(String driveName) {
		if (!DLFSPathValidator.isValidDriveName(driveName)) return null;
		AString name=Strings.create(driveName);
		AHashMap<AString,AVector<ACell>> registry=cursor.get();
		if (registry==null || status(registry.get(name))!=DriveStatus.LIVE) return null;
		return drives.compute(name,(key,existing)->{
			if (existing!=null && existing.isOpen()) return existing;
			return configured(new DLFSDrive(key,resolve(cursor,LATTICE,key),store,location.child(key)));
		});
	}

	/** Creates a drive subject to an atomic owner-specific count limit. */
	public DLFSDrive createDrive(String driveName, long maximumDrives) {
		if (!DLFSPathValidator.isValidDriveName(driveName) || maximumDrives<1) return null;
		return acquireDrive(driveName,maximumDrives,false);
	}

	/**
	 * Opens an existing drive or atomically creates it without a count limit.
	 * See {@link #getOrCreateDrive(String, long)} for failure and concurrency semantics.
	 */
	public DLFSDrive getOrCreateDrive(String driveName) {
		return getOrCreateDrive(driveName,Long.MAX_VALUE);
	}

	/**
	 * Opens an existing live drive without changing its contents or timestamp, or
	 * atomically creates it subject to an owner-specific live-drive limit. Concurrent
	 * creators sharing the same working state open the winning drive. Independent
	 * forks enforce the limit locally; it is not a quota across replicas.
	 *
	 * <p>Returns null for an invalid name, a malformed entry, or exhausted capacity.
	 * A non-positive limit prevents creation but still permits opening an existing
	 * drive. Deleted entries may be recreated and do not consume capacity.</p>
	 *
	 * <p>Creation uses this component's clock and signing policy. No publication is
	 * triggered. A concurrent deletion may cause this call to return null, or may
	 * delete the drive after it is returned; the returned view is not a lifetime
	 * guarantee. Deletion does not trigger another creation attempt.</p>
	 *
	 * @param driveName Name within this owner's drive map
	 * @param maximumDrives Maximum live drives allowed when creating
	 * @return Connected drive view, or null if unavailable
	 */
	public DLFSDrive getOrCreateDrive(String driveName, long maximumDrives) {
		if (!DLFSPathValidator.isValidDriveName(driveName)) return null;
		DLFSDrive existing=drive(driveName);
		if (existing!=null) return existing;
		return acquireDrive(driveName,maximumDrives,true);
	}

	private DLFSDrive acquireDrive(String driveName, long maximumDrives, boolean openExisting) {
		AString name=Strings.create(driveName);
		CVMLong operationTime=mutationTimestamp();
		AHashMap<AString,AVector<ACell>> previous=cursor.getAndUpdate(registry->{
			if (!canCreate(registry,name,maximumDrives)) return registry;
			if (registry==null) registry=Maps.empty();
			return registry.assoc(name,DLFSNode.createDirectory(operationTime));
		});
		AVector<ACell> old=(previous==null)?null:previous.get(name);
		if (status(old)==DriveStatus.LIVE) return openExisting ? drive(driveName) : null;
		if (!canCreate(previous,name,maximumDrives)) return null;
		return drive(driveName);
	}

	private static boolean canCreate(AHashMap<AString,AVector<ACell>> registry,
			AString name, long maximumDrives) {
		if (maximumDrives<1) return false;
		AVector<ACell> existing=(registry==null)?null:registry.get(name);
		if (existing!=null && status(existing)!=DriveStatus.DELETED) return false;
		return registry==null || maximumDrives==Long.MAX_VALUE || liveCount(registry)<maximumDrives;
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
		if (oldName.equals(newName)) return driveStatus(oldName)==DriveStatus.LIVE;
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
			AVector<ACell> movedRoot=DLFSNode.withMTime(root,DLFSNode.getMTime(root)).assoc(DLFSNode.POS_UTIME,operationTime);
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
		return getContext().currentTimestamp();
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
	@Override
	public DLFSDrives withCursor(AForkableCursor<AHashMap<AString, AVector<ACell>>> cursor) {
		return configured(new DLFSDrives(cursor,store));
	}

}
