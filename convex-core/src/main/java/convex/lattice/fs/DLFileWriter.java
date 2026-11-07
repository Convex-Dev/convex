package convex.lattice.fs;

import java.io.IOException;
import java.io.OutputStream;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.util.BlobOutputStream;

/**
 * Output stream that writes a new version of a DLFS file and publishes it with
 * an explicit {@link #commit()}.
 *
 * Nothing is visible in the drive until commit, which installs the content in
 * one atomic update. Closing without commit discards the content, so a failure
 * partway through, such as a source read error, publishes nothing. On a drive
 * with a store, content is saved there as it is written, so memory use stays
 * bounded; otherwise it is held in memory until commit.
 */
public final class DLFileWriter extends OutputStream {

	private final DLFileSystem fileSystem;
	private final DLPath path;
	private final DLFSCondition condition;
	private final BlobOutputStream content;
	private boolean finished=false;

	DLFileWriter(DLFileSystem fileSystem, DLPath path, DLFSCondition condition, BlobOutputStream content) {
		this.fileSystem=fileSystem;
		this.path=path;
		this.condition=condition;
		this.content=content;
	}

	@Override
	public void write(int b) throws IOException {
		checkActive();
		content.write(b);
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		checkActive();
		content.write(b,off,len);
	}

	/**
	 * Publishes the content written, if the condition holds for the file's
	 * current entry. The writer is finished afterwards, whether or not the
	 * condition held.
	 * @return The entry replaced, or null if the file was created
	 * @throws ConditionFailedException If the condition does not hold; nothing changes
	 * @throws IOException In case of other IO error
	 */
	public AVector<ACell> commit() throws IOException {
		checkActive();
		finished=true;
		return fileSystem.install(path,content.toBlob(),condition);
	}

	/**
	 * Finishes the writer. Content not committed is discarded.
	 */
	@Override
	public void close() {
		finished=true;
	}

	private void checkActive() throws IOException {
		if (finished) throw new IOException("Writer already committed or closed: "+path);
	}
}
