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
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.fs.DLFSLattice;
import convex.lattice.generic.MapLattice;

/** Component representing all named drives belonging to one owner. */
public final class DLFSDrives extends ALatticeComponent<AHashMap<AString, AVector<ACell>>> {

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
		if (registry==null || registry.get(name)==null) return null;
		return drives.compute(name,(key,existing)->{
			if (existing!=null && existing.isOpen()) return existing;
			return new DLFSDrive(this,key,cursor.path(key));
		});
	}

	/** Creates a drive subject to an atomic owner-specific count limit. */
	public DLFSDrive createDrive(String driveName, long maximumDrives) {
		if (!DLFSPathValidator.isValidDriveName(driveName) || maximumDrives<1) return null;
		AString name=Strings.create(driveName);
		AHashMap<AString,AVector<ACell>> previous=cursor.getAndUpdate(registry->{
			if (registry==null) registry=Maps.empty();
			if (registry.containsKey(name) || registry.count()>=maximumDrives) return registry;
			return registry.assoc(name,DLFSLattice.INSTANCE.zero());
		});
		if ((previous!=null && previous.containsKey(name))
				|| (previous!=null && previous.count()>=maximumDrives)) return null;
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
		AHashMap<AString,AVector<ACell>> previous=cursor.getAndUpdate(registry->{
			if (registry==null || !registry.containsKey(name)) return registry;
			return registry.dissoc(name);
		});
		boolean deleted=previous!=null && previous.containsKey(name);
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
		AHashMap<AString,AVector<ACell>> previous=cursor.getAndUpdate(registry->{
			if (registry==null || registry.containsKey(newKey)) return registry;
			AVector<ACell> root=registry.get(oldKey);
			if (root==null) return registry;
			return registry.assoc(newKey,root).dissoc(oldKey);
		});
		if (previous==null || previous.get(oldKey)==null || previous.containsKey(newKey)) return false;
		closeCached(oldKey);
		closeCached(newKey);
		return true;
	}

	/** Returns sorted drive names belonging to this owner. */
	public List<String> driveNames() {
		AHashMap<AString,AVector<ACell>> registry=cursor.get();
		if (registry==null || registry.isEmpty()) return List.of();
		List<String> result=new ArrayList<>((int)Math.min(Integer.MAX_VALUE,registry.count()));
		for (AString name: registry.keySet()) result.add(name.toString());
		result.sort(String::compareTo);
		return result;
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
