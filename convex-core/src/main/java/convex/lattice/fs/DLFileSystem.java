package convex.lattice.fs;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.Collections;
import java.util.Set;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.util.BlobOutputStream;
import convex.core.store.AStore;
import convex.lattice.fs.impl.DLDirectoryStream;
import convex.lattice.fs.impl.DLFSFileAttributes;

/**
 * Base class for Data Lattice FileSystems.
 * 
 * A Data Lattice FileSystem has:
 * - A single root directory
 * - A method of snapshotting any path on the tree
 * - An efficient method of cloning the Drive with an immutable snapshot
 */
public abstract class DLFileSystem extends FileSystem implements Cloneable {

	/** Number of streamed bytes between opportunities to persist blob data. */
	public static final int BLOB_PERSIST_INTERVAL = 16 * 1024 * 1024;

	static final String SEP = "/";

	private static final Set<String> SUPPORTED_FILE_ATTRIBUTE_SET = Collections.singleton("basic");

	protected final DLFSProvider provider;
	private volatile boolean open = true;
	
	// Singleton root / empty paths
	protected final DLPath root=new DLPath(this,DLPath.EMPTY_STRINGS,true);
	protected final DLPath emptyPath=new DLPath(this);

	protected final String uriPath;
	
	protected DLFileSystem(DLFSProvider dlfsProvider, String uriPath) {
		this.provider=dlfsProvider;
		this.uriPath=uriPath;
	}

	@Override
	public DLFSProvider provider() {
		return provider;
	}

	/** Whether both views address the same working drive, rather than equal snapshots. */
	public boolean isSameDrive(DLFileSystem other) {
		return this==other;
	}

	/** Closes this view and its I/O handles without publishing or closing the store. */
	@Override
	public void close() throws IOException {
		open = false;
	}

	/** Rejects I/O through this view after close, including through existing paths. */
	protected final void checkOpen() {
		if (!open) throw new java.nio.file.ClosedFileSystemException();
	}
	
	@Override
	public boolean isOpen() {
		return open;
	}

	@Override
	public boolean isReadOnly() {
		return false;
	}

	@Override
	public String getSeparator() {
		return SEP;
	}

	@Override
	public Iterable<Path> getRootDirectories() {
		return Collections.singletonList(root);
	}

	@Override
	public Iterable<FileStore> getFileStores() {
		// TODO should we have special stores?
		return Collections.emptyList();
	}

	@Override
	public Set<String> supportedFileAttributeViews() {
		return SUPPORTED_FILE_ATTRIBUTE_SET;
	}

	@Override
	public DLPath getPath(String first, String... more) {
		checkOpen();
		String fullPath=first;
		if ((more!=null)&&(more.length>0)) {
			fullPath=fullPath+SEP+String.join(SEP,more);
		}
		return DLPath.create(this,fullPath);
	}

	@Override
	public PathMatcher getPathMatcher(String syntaxAndPattern) {
		throw new UnsupportedOperationException("DLFS path matchers are not implemented");
	}

	@Override
	public UserPrincipalLookupService getUserPrincipalLookupService() {
		throw new UnsupportedOperationException();
	}

	@Override
	public WatchService newWatchService() throws IOException {
		throw new UnsupportedOperationException();
	}

	/**
	 * Gets the unique root path for this FileSystem
	 * @return Path instance to root
	 */
	public DLPath getRoot() {
		return root;
	}
	
	/**
	 * Gets an empty path for this FileSystem
	 * @return empty path instance
	 */
	public DLPath getEmptyPath() {
		return emptyPath;
	}

	/**
	 * Implementation for delegation by DLFSProvider
	 * @param path Path for new file
	 * @param options Options for file creation
	 * @param attrs File attributes
	 * @return ByteChannel instance
	 * @throws IOException In case of IO Error
	 */
	public abstract SeekableByteChannel newByteChannel(DLPath path, Set<? extends OpenOption> options, FileAttribute<?>[] attrs) throws IOException;

	/**
	 * Installs content as a file in one atomic update of the drive, if the
	 * condition holds for the file's current entry. Readers see the old file or
	 * the new one, never part of either. The parent directory must exist.
	 *
	 * <p>A new file gets a fresh entry. An existing file keeps its metadata and
	 * any unknown trailing fields; its content and content time change. A deleted
	 * name counts as absent. The content is saved to the drive's store, if any,
	 * before the update, so a retry under contention redoes only the install.</p>
	 *
	 * @param path File to write, in this filesystem
	 * @param content Complete new content
	 * @param condition Condition on the current entry
	 * @return The entry replaced, or null if the file was created
	 * @throws ConditionFailedException If the condition does not hold; nothing changes
	 * @throws IOException If the parent is missing or not a directory, the path is a directory, or in case of other IO error
	 */
	public abstract AVector<ACell> install(DLPath path, ABlob content, DLFSCondition condition) throws IOException;

	/**
	 * Gets the store that file content is saved to as it is written
	 * @return Store, or null to keep content in memory
	 */
	protected abstract AStore getContentStore();

	/**
	 * Creates a writer for a new version of a file. Nothing is visible until
	 * {@link DLFileWriter#commit()}, which installs the content subject to the
	 * condition; closing without commit discards it.
	 *
	 * @param path File to write, in this filesystem
	 * @param condition Condition on the file's entry when committing
	 * @return New writer
	 */
	public DLFileWriter newWriter(DLPath path, DLFSCondition condition) {
		checkOpen();
		if (path.getFileSystem()!=this) throw new IllegalArgumentException("Path belongs to another filesystem");
		return new DLFileWriter(this,path,condition,BlobOutputStream.create(getContentStore()));
	}

	/**
	 * Writes a file from a stream, publishing it only if the stream ends cleanly.
	 *
	 * @param path File to write, in this filesystem
	 * @param in Stream of complete new content
	 * @param condition Condition on the file's entry when the stream ends
	 * @return The entry replaced, or null if the file was created
	 * @throws ConditionFailedException If the condition does not hold; nothing changes
	 * @throws IOException If the stream fails, or in case of other IO error; nothing changes
	 */
	public AVector<ACell> write(DLPath path, InputStream in, DLFSCondition condition) throws IOException {
		try (DLFileWriter writer=newWriter(path,condition)) {
			in.transferTo(writer);
			return writer.commit();
		}
	}

	/**
	 * Replaces a complete file in one atomic update.
	 *
	 * @param path File to write, in this filesystem
	 * @param data Complete new content, copied
	 * @return true if the file was created, false if it replaced an existing file
	 * @throws IOException If the parent is missing or not a directory, the path is a directory, or in case of other IO error
	 */
	public final boolean writeAllBytes(DLPath path, byte[] data) throws IOException {
		return write(path,new ByteArrayInputStream(data),DLFSCondition.ANY)==null;
	}

	/**
	 * Implementation for delegation by DLFSProvider
	 * @return Directory stream
	 */
	protected abstract DLDirectoryStream newDirectoryStream(DLPath dir, Filter<? super Path> filter) throws IOException;

	public DLFSFileAttributes getFileAttributes(DLPath path) throws IOException {
		AVector<ACell> node=getNode(path);
		if (node==null) {
			throw new java.nio.file.NoSuchFileException(path.toString());
		}
		return DLFSFileAttributes.create(node);
	}

	/** Sets independent NIO file times using the filesystem's mutation policy. */
	public void setTimes(DLPath path,FileTime modified,FileTime accessed,FileTime created) throws IOException {
		throw new UnsupportedOperationException("File timestamps cannot be edited by this implementation");
	}

	/**
	 * Gets DLFS node for the given path
	 * @param path Path for which to obtain DLFSNode
	 * @return DLFS node, or null if does not exist
	 */
	public abstract AVector<ACell> getNode(DLPath path) throws IOException;

	/**
	 * Implementation for delegation by DLFSProvider, create a directory
	 * @return
	 * @throws IOException 
	 */
	protected abstract DLPath createDirectory(DLPath dir, FileAttribute<?>[] attrs) throws IOException;

	/**
	 * Implementation for DLFSProvider delegation
	 * @param path
	 * @throws IOException 
	 */
	protected abstract void checkAccess(DLPath path) throws IOException;

	public abstract void delete(DLPath dlp) throws IOException;

	/**
	 * Copies a node within this drive.
	 *
	 * @param source source path
	 * @param target target path
	 * @param recursive true to copy a complete directory subtree
	 * @throws IOException if the copy cannot be completed
	 */
	public abstract void copy(DLPath source, DLPath target, boolean recursive) throws IOException;

	/**
	 * Copies a node within this drive, optionally replacing an existing target.
	 *
	 * @param source source path
	 * @param target target path
	 * @param recursive true to copy a complete directory subtree
	 * @param replaceExisting true to replace an existing target
	 * @throws IOException if the copy cannot be completed
	 */
	public void copy(DLPath source, DLPath target, boolean recursive, boolean replaceExisting) throws IOException {
		if (replaceExisting) throw new UnsupportedOperationException("Replacing an existing DLFS target is not supported");
		copy(source,target,recursive);
	}

	/** Copies with optional preservation of independent file timestamps. */
	public void copy(DLPath source,DLPath target,boolean recursive,boolean replaceExisting,boolean copyAttributes) throws IOException {
		if (copyAttributes) throw new UnsupportedOperationException("Copying file attributes is not supported");
		copy(source,target,recursive,replaceExisting);
	}

	/**
	 * Moves a node within this drive.
	 *
	 * @param source source path
	 * @param target target path
	 * @throws IOException if the move cannot be completed
	 */
	public abstract void move(DLPath source, DLPath target) throws IOException;

	/**
	 * Moves a node within this drive, optionally replacing an existing target.
	 *
	 * @param source source path
	 * @param target target path
	 * @param replaceExisting true to replace an existing target
	 * @throws IOException if the move cannot be completed
	 */
	public void move(DLPath source, DLPath target, boolean replaceExisting) throws IOException {
		if (replaceExisting) throw new UnsupportedOperationException("Replacing an existing DLFS target is not supported");
		move(source,target);
	}

	public abstract Hash getRootHash();

	public Hash getNodeHash(DLPath p) throws IOException {
		AVector<ACell> node=getNode(p);
		if (node==null) return null;
		return Cells.getHash(node);
	}

	/**
	 * Creates a file, returning the new node
	 * @param path Path for which to create DLFSNode
	 * @return New file node
	 * @throws IOException In event of IOError
	 */
	public abstract AVector<ACell> createFile(DLPath path) throws IOException;

	/**
	 * Updates a node, returning the new node 
	 * @param path Path for which to update DLFSNode
	 * @param newNode NEw node to put in place
	 * @return The new node
	 */
	public abstract AVector<ACell> updateNode(DLPath path, AVector<ACell> newNode) throws IOException;
	

	/**
	 * Merges another DLFS drive into this one
	 * @param other Root node of other DLFS drive
	 */
	public abstract void merge(AVector<ACell> other);

	public void replicate(DLFileSystem other) throws IOException {
		merge(other.getNode(other.getRoot()));
	}

	/**
	 * Creates an independent fork for isolated batch operations.
	 * Changes to the fork do not affect this filesystem until {@link #sync()}
	 * is called on the fork.
	 * @return A new forked filesystem with its own local cursor
	 */
	public abstract DLFileSystem fork();

	/**
	 * Syncs local changes back to the parent cursor using lattice merge.
	 * Only meaningful on forked filesystems — calling on a root filesystem is a no-op.
	 */
	public abstract void sync();

	@Override
	public abstract DLFileSystem clone();
	
}
