package convex.dlfs;

import java.io.IOException;
import java.util.function.Supplier;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.store.AStore;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.Cursors;
import convex.lattice.ALatticeComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.fs.DLFS;
import convex.lattice.fs.DLFSLattice;
import convex.lattice.fs.DLFSLocation;
import convex.lattice.fs.DLFileSystem;
import convex.lattice.fs.impl.DLFSLocal;

/**
 * Component for one named DLFS drive.
 *
 * <p>The component supplies its cursor and store to a local NIO adapter. Streamed
 * writes checkpoint blob branches into the store, if one was supplied, so large
 * files do not accumulate on the heap; without a store file data stays in memory
 * until the containing root is published. {@link #fork()} creates a temporary
 * working component, sharing the store, whose changes remain isolated until
 * explicitly synced.</p>
 */
public final class DLFSDrive extends ALatticeComponent<AVector<ACell>> implements AutoCloseable, TransactionalComponent<AVector<ACell>,DLFSDrive> {

	private final AString name;
	private final AStore store;
	private final Supplier<CVMLong> clock;
	private final DLFSLattice lattice;
	private final DLFSLocation location;
	private final HostedFileSystem fileSystem;

	DLFSDrive(AString name, AForkableCursor<AVector<ACell>> cursor, AStore store, DLFSLocation location,
			Supplier<CVMLong> clock,DLFSLattice lattice) {
		super(cursor);
		this.name=name;
		this.store=store;
		this.location=location;
		this.clock=clock;
		this.lattice=lattice;
		this.fileSystem=new HostedFileSystem(this);
	}

	/** Returns this drive's name within its owner's drive map. */
	public String name() {
		return name.toString();
	}

	/** This component's working root and logical region/owner/drive path. */
	public DLFSLocation location() { return location; }

	/** Returns the NIO filesystem view backed by this component's cursor. */
	public DLFileSystem fileSystem() {
		return fileSystem;
	}

	/** Returns true while this component's NIO view remains open. */
	public boolean isOpen() {
		return fileSystem.isOpen();
	}

	/**
	 * Creates an isolated temporary drive component. The filesystem still checkpoints
	 * into the same store, while logical changes require explicit {@link #sync()},
	 * which merges with {@link DLFSLattice}, to reach the long-lived drive cursor.
	 */
	public DLFSDrive fork() {
		return new DLFSDrive(this);
	}

	private DLFSDrive(DLFSDrive source) {
		super(source);
		this.name=source.name;
		this.store=source.store;
		this.location=DLFSLocation.at(cursor());
		this.clock=source.clock;
		this.lattice=source.lattice;
		this.fileSystem=new HostedFileSystem(this);
	}

	@Override
	public void close() throws IOException {
		fileSystem.close();
	}

	/** NIO boundary retaining component-aware fork behaviour. */
	private static final class HostedFileSystem extends DLFSLocal {
		private final DLFSDrive drive;

		HostedFileSystem(DLFSDrive drive) {
			super(DLFS.provider(),drive.name(),drive.cursor(),drive.store,drive.location);
			this.drive=drive;
		}

		@Override protected CVMLong currentTimestamp() { return drive.clock.get(); }
		@Override public void merge(AVector<ACell> incoming) {
			drive.cursor().updateAndGet(own -> drive.lattice.merge(own,incoming));
		}
		@Override public DLFSLocal clone() {
			return drive.withCursor(Cursors.root(drive.cursor().get())).fileSystem;
		}
		@Override public void sync() { drive.sync(); }

		@Override
		public DLFSLocal fork() {
			return drive.fork().fileSystem;
		}
	}

	@Override
	public DLFSDrive withCursor(AForkableCursor<AVector<ACell>> cursor) {
		return new DLFSDrive(name,cursor,store,DLFSLocation.at(cursor),clock,lattice);
	}

	/** Reconciles this component's draft using its domain merge rule. */
	@Override
	protected AVector<ACell> mergePublication(AVector<ACell> own,AVector<ACell> other) {
		return lattice.merge(own,other);
	}
}
