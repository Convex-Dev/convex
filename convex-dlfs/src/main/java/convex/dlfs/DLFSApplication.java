package convex.dlfs;

import java.io.IOException;
import java.util.Objects;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.store.AStore;
import convex.lattice.ALatticeApplication;
import convex.lattice.Lattice;
import convex.lattice.LatticeContext;
import convex.lattice.RootComponent;
import convex.lattice.cursor.ALatticeCursor;

/**
 * Application-level component for a lattice application providing DLFS.
 *
 * <p>The application works at the complete hosted lattice root and composes a
 * physical {@link DLFSRegion} at a configured path. It is not scoped to one
 * owner: callers select any owner's {@link DLFSDrives} component as needed.
 * Subclasses may attach further application facilities, such as P2P discovery,
 * beneath this same application component.</p>
 *
 * <p>DLFS is the one application whose components need a store before
 * publication: streamed file writes checkpoint blob branches so large files do
 * not accumulate on the heap. The application therefore takes the host's store
 * and hands it down to regions, drive maps and drives explicitly. It owns neither
 * the host nor the store lifecycle; {@link #sync()} publishes the complete
 * application root through host policy and durability is the host's
 * {@link RootComponent#flush()}.</p>
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
		this(Objects.requireNonNull(host,"DLFS application host must not be null").cursor().view(),
			host.store(),regionPath);
	}

	/**
	 * Binds a working view, such as a fork or transaction draft, to a cursor.
	 *
	 * @param cursor Cursor at the complete application root
	 * @param store Store for incremental blob checkpoints, or null for heap-only file data
	 * @param regionPath Path from the application root to the physical DLFS region
	 */
	protected DLFSApplication(ALatticeCursor<V> cursor, AStore store, ACell... regionPath) {
		super(cursor);
		if (regionPath==null) throw new IllegalArgumentException("DLFS region path must not be null");
		this.store=store;
		this.regionPath=regionPath.clone();
	}

	/**
	 * Opens a local application with DLFS in the standard {@code :fs} region.
	 *
	 * <p>An existing retained root is restored from the supplied store. An empty
	 * store starts at {@link Lattice#ROOT}'s zero value. The supplied key pair is
	 * installed as the root merge context for owner-signed writes made by this
	 * application instance.</p>
	 *
	 * @param store Store used for persistence and retained root publication
	 * @param keyPair Application signing key pair
	 * @return Local DLFS application
	 * @throws IOException If the retained root cannot be read
	 */
	public static DLFSApplication<Index<Keyword,ACell>> open(AStore store,
			AKeyPair keyPair) throws IOException {
		if (keyPair==null) throw new IllegalArgumentException("Application key pair must not be null");
		RootComponent<Index<Keyword,ACell>> root=RootComponent.open(Lattice.ROOT,store);
		root.cursor().setContext(LatticeContext.create(null,keyPair));
		return connect(root,Keywords.FS);
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

	/** Returns the physical multi-owner DLFS region. */
	public DLFSRegion dlfs() {
		return DLFSRegion.connect(cursor,store,regionPath);
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
