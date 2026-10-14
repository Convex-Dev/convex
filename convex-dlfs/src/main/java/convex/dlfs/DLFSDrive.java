package convex.dlfs;

import java.io.IOException;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.lattice.ALatticeComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.fs.DLFS;
import convex.lattice.fs.DLFileSystem;
import convex.lattice.fs.impl.DLFSLocal;

/**
 * Component for one named DLFS drive.
 *
 * <p>The component supplies its cursor to a local NIO adapter. Checkpoints use
 * the cursor's host persistence capability and the component's resolved policy.
 * {@link #fork()} creates a temporary working component whose changes remain
 * isolated until explicitly synced.</p>
 */
public final class DLFSDrive extends ALatticeComponent<AVector<ACell>> implements AutoCloseable, TransactionalComponent<AVector<ACell>,DLFSDrive> {

	private final AString name;
	private final HostedFileSystem fileSystem;

	DLFSDrive(ALatticeComponent<?> parent, AString name, ALatticeCursor<AVector<ACell>> cursor) {
		super(parent,cursor);
		this.name=name;
		this.fileSystem=new HostedFileSystem(this);
	}

	/** Returns this drive's name within its owner's drive map. */
	public String name() {
		return name.toString();
	}

	/** Returns the NIO filesystem view backed by this component's cursor. */
	public DLFileSystem fileSystem() {
		return fileSystem;
	}

	/** Returns true while this component's NIO view remains open. */
	public boolean isOpen() {
		return fileSystem.isOpen();
	}

	/**
	 * Creates an isolated temporary drive component. The filesystem still uses the
	 * containing host's store, while logical changes require explicit {@link #sync()}
	 * to reach the long-lived drive cursor.
	 */
	public DLFSDrive fork() {
		return new DLFSDrive(this,name,cursor.fork());
	}

	@Override
	public void close() throws IOException {
		fileSystem.close();
	}

	/** NIO boundary retaining component-aware fork behaviour. */
	private static final class HostedFileSystem extends DLFSLocal {
		private final DLFSDrive drive;

		HostedFileSystem(DLFSDrive drive) {
			super(DLFS.provider(),drive.name(),drive.cursor());
			this.drive=drive;
		}

		@Override
		protected ABlob checkpointBlob(ABlob data) throws IOException {
			return drive.checkpoint(data);
		}

		@Override
		public DLFSLocal fork() {
			return drive.fork().fileSystem;
		}
	}
	@Override
	public DLFSDrive withCursor(ALatticeCursor<AVector<ACell>> cursor) {
		return new DLFSDrive(this,name,cursor);
	}

}
