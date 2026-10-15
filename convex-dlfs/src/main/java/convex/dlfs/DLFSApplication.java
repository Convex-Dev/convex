package convex.dlfs;

import java.util.Objects;

import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.store.AStore;
import convex.lattice.ALattice;
import convex.lattice.ALatticeApplication;
import convex.lattice.RootComponent;
import convex.lattice.cursor.AForkableCursor;

/**
 * Application-level component for a lattice application providing DLFS.
 *
 * <p>The application works at the complete hosted lattice root and composes a
 * physical {@link DLFSRegion} at a configured path, navigated with the root
 * lattice so the region can be created when absent. It is not scoped to one
 * owner: callers select any owner's {@link DLFSDrives} component as needed.
 * Subclasses may attach further application facilities, such as P2P discovery,
 * beneath this same application component.</p>
 *
 * <p>DLFS is the one application whose components need a store before
 * publication: streamed file writes checkpoint blob branches so large files do
 * not accumulate on the heap. The application therefore takes the host's store
 * and hands it down to regions, drive maps and drives explicitly. It owns neither
 * the host nor the store lifecycle. This adapter is a live view; its edits are
 * already in the host's working state. Explicit {@link RootComponent#sync()}
 * publishes that state, and {@link RootComponent#flush()} provides durability.</p>
 *
 * @param <V> Complete application root value type
 */
public class DLFSApplication<V extends ACell> extends ALatticeApplication<V> {

	private final AStore store;
	private final ACell[] regionPath;

	/**
	 * Creates an extensible DLFS application over a host.
	 *
	 * @param host Generic lattice application host
	 * @param regionPath Path from the application root to the physical DLFS region
	 */
	protected DLFSApplication(RootComponent<V> host, ACell... regionPath) {
		this(Objects.requireNonNull(host,"DLFS application host must not be null").getLattice(),
			host.cursor(),host.store(),regionPath);
		setContext(host.getContext());
	}

	/**
	 * Binds a working view, such as a fork or transaction draft, to a cursor.
	 *
	 * @param lattice Root lattice of the complete application root
	 * @param cursor Cursor at the complete application root
	 * @param store Store for incremental blob checkpoints, or null for heap-only file data
	 * @param regionPath Path from the application root to the physical DLFS region
	 */
	protected DLFSApplication(ALattice<V> lattice, AForkableCursor<V> cursor, AStore store, ACell... regionPath) {
		super(lattice,cursor);
		if (regionPath==null) throw new IllegalArgumentException("DLFS region path must not be null");
		this.store=store;
		this.regionPath=regionPath.clone();
	}

	/**
	 * Connects a DLFS application to a region at an arbitrary path beneath an
	 * existing hosted lattice root.
	 *
	 * @param <V> Complete application root value type
	 * @param host Generic lattice application host
	 * @param regionPath Path from the application root to the physical DLFS region
	 * @return Connected DLFS application
	 */
	public static <V extends ACell> DLFSApplication<V> connect(RootComponent<V> host,
			ACell... regionPath) {
		return new DLFSApplication<>(host,regionPath);
	}

	/** Returns the store used for blob checkpoints, or null when file data is heap-only. */
	protected AStore store() {
		return store;
	}

	/** Returns the physical multi-owner DLFS region, navigated with the root lattice. */
	public DLFSRegion dlfs() {
		return configured(DLFSRegion.connect(cursor,getLattice(),store,regionPath));
	}

	/**
	 * Returns the component representing one owner's named drives.
	 *
	 * @param owner Drive owner
	 * @return Owner-scoped drives component
	 */
	public DLFSDrives drives(AccountKey owner) {
		return dlfs().drives(owner);
	}

}
