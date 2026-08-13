package convex.lattice.fs;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchService;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.Collections;
import java.util.Set;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;
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

	static final String SEP = "/";

	private static final Set<String> SUPPORTED_FILE_ATTRIBUTE_SET = Collections.singleton("basic");

	protected final DLFSProvider provider;
	private CVMLong timestamp; 
	private volatile boolean open = true;
	
	// Singleton root / empty paths
	protected final DLPath root=new DLPath(this,DLPath.EMPTY_STRINGS,true);
	protected final DLPath emptyPath=new DLPath(this);

	protected final String uriPath;
	
	protected DLFileSystem(DLFSProvider dlfsProvider, String uriPath, CVMLong timestamp) {
		this.provider=dlfsProvider;
		this.uriPath=uriPath;
		this.timestamp=timestamp;
	}

	@Override
	public DLFSProvider provider() {
		return provider;
	}

	@Override
	public void close() throws IOException {
		open = false;
	}
	
	/**
	 * Gets the timestamp of this DLFS drive, used to mark new writes.
	 * Subclasses may override to consult a cursor's {@link convex.lattice.LatticeContext}.
	 *
	 * @return Current timestamp as a CVM integer
	 */
	public CVMLong getTimestamp() {
		return timestamp;
	}
	
	/**
	 * Sets the timestamp of this DLFS drive
	 * @param newTimestamp New timestamp
	 */
	public final void setTimestamp(CVMLong newTimestamp) {
		timestamp=newTimestamp;
	}
	
	/**
	 * Updates the timestamp of this DLFS drive to the maximum of the given timestamp or it's current time stamp
	 * @param newTimestamp Potential new timestamp
	 * @return The new timestamp value, or the original one if unchanged
	 */
	public synchronized CVMLong updateTimestamp(long newTimestamp) {
		if (newTimestamp>timestamp.longValue()) {
			timestamp=CVMLong.create(newTimestamp);
		}
		return timestamp;
	}
	
	/**
	 * Updates the timestamp of the drive to the current system timestamp
	 */
	public synchronized CVMLong updateTimestamp() {
		long current=timestamp.longValue();
		long now=Utils.getCurrentTimestamp();
		// Wall-clock resolution is commonly one millisecond. Ensure successive local
		// logical mutations never receive an accidental tie within the same tick.
		long next=(now>current)?now:((current<Long.MAX_VALUE)?current+1:current);
		timestamp=CVMLong.create(next);
		return timestamp;
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
		if (!open) throw new java.nio.file.ClosedFileSystemException();
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
	 * Replaces a complete file while holding the filesystem mutation lock.
	 *
	 * <p>This is intended for request-oriented APIs such as WebDAV where one PUT is
	 * one logical mutation. It prevents the channel-open truncate and subsequent
	 * write from interleaving with another whole-file replacement.</p>
	 *
	 * @param path destination path in this filesystem
	 * @param data complete new file content
	 * @return true if the file was created, false if it replaced an existing file
	 */
	public final synchronized boolean writeAllBytes(DLPath path, byte[] data) throws IOException {
		if (path == null || path.getFileSystem() != this) throw new IllegalArgumentException("Path belongs to another filesystem");
		boolean created = getNode(path) == null;
		Set<StandardOpenOption> options = Set.of(StandardOpenOption.CREATE,
			StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
		try (SeekableByteChannel channel = newByteChannel(path, options, new FileAttribute<?>[0])) {
			ByteBuffer source = ByteBuffer.wrap(data);
			while (source.hasRemaining()) channel.write(source);
		}
		return created;
	}

	/**
	 * Implementation for delegation by DLFSProvider
	 * @return Directory stream
	 */
	protected abstract DLDirectoryStream newDirectoryStream(DLPath dir, Filter<? super Path> filter) throws IOException;

	DLFSFileAttributes getFileAttributes(DLPath path) throws IOException {
		AVector<ACell> node=getNode(path);
		if (node==null) {
			throw new java.nio.file.NoSuchFileException(path.toString());
		}
		return DLFSFileAttributes.create(node);
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
	 * Moves a node within this drive.
	 *
	 * @param source source path
	 * @param target target path
	 * @throws IOException if the move cannot be completed
	 */
	public abstract void move(DLPath source, DLPath target) throws IOException;

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
