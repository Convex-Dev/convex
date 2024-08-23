package convex.dlfs;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
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
import convex.dlfs.impl.DLDirectoryStream;
import convex.dlfs.impl.DLFSFileAttributes;

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
		
	}
	
	/**
	 * Gets the timestamp of this DLFS drive
	 * @return Current timestamp as a CVM integer
	 */
	public final CVMLong getTimestamp() {
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
		return updateTimestamp(Utils.getCurrentTimestamp());
	}

	@Override
	public boolean isOpen() {
		return true;
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
		String fullPath=first;
		if ((more!=null)&&(more.length>0)) {
			fullPath=fullPath+SEP+String.join(SEP,more);
		}
		return DLPath.create(this,fullPath);
	}

	@Override
	public PathMatcher getPathMatcher(String syntaxAndPattern) {
		// TODO Auto-generated method stub
		return null;
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
	 * Implementation for delegation by DLFSProvider
	 * @return Directory stream
	 */
	protected abstract DLDirectoryStream newDirectoryStream(DLPath dir, Filter<? super Path> filter);

	DLFSFileAttributes getFileAttributes(DLPath path) {
		AVector<ACell> node=getNode(path);
		return DLFSFileAttributes.create(node);
	}

	/**
	 * Gets DLFS node for the given path
	 * @param path Path for which to obtain DLFSNode
	 * @return DLFS node, or null if does not exist
	 */
	public abstract AVector<ACell> getNode(DLPath path);

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

	public abstract Hash getRootHash();

	public Hash getNodeHash(DLPath p) {
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
	public abstract AVector<ACell> updateNode(DLPath path, AVector<ACell> newNode);
	

	/**
	 * Merges another DLFS drive into this one
	 * @param other Root node of other DLFS drive
	 */
	public abstract void merge(AVector<ACell> other);

	public void replicate(DLFileSystem other) {
		merge(other.getNode(other.getRoot()));
	}
	
	@Override 
	public abstract DLFileSystem clone();
	
}
