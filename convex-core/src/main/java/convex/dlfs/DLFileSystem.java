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
import java.nio.file.spi.FileSystemProvider;
import java.util.Collections;
import java.util.Set;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.dlfs.impl.DLDirectoryStream;
import convex.dlfs.impl.DLFSFileAttributes;

/**
 * Base class for Data Lattice FileSystems.
 * 
 * A Data Lattice FileSystem has:
 * - A single root directory
 * - A method of snapshotting any path on the tree
 */
public abstract class DLFileSystem extends FileSystem {

	static final String SEP = "/";

	protected final DLFSProvider provider;
	
	// Singleton root / empty paths
	protected final DLPath root=new DLPath(this,DLPath.EMPTY_STRINGS,true);
	protected final DLPath emptyPath=new DLPath(this);

	protected final String uriPath;
	
	protected DLFileSystem(DLFSProvider dlfsProvider, String uriPath) {
		this.provider=dlfsProvider;
		this.uriPath=uriPath;
	}

	@Override
	public FileSystemProvider provider() {
		return provider;
	}

	@Override
	public void close() throws IOException {
		
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
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Path getPath(String first, String... more) {
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
	 * @return
	 */
	public DLPath getRoot() {
		return root;
	}
	
	/**
	 * Gets the unique root path for this FileSystem
	 * @return
	 */
	public DLPath getEmptyPath() {
		return emptyPath;
	}

	/**
	 * Implementation for delegation by DLFSProvider
	 * @param path
	 * @param options
	 * @param attrs
	 * @return
	 * @throws IOException 
	 */
	public abstract SeekableByteChannel newByteChannel(DLPath path, Set<? extends OpenOption> options, FileAttribute<?>[] attrs) throws IOException;

	/**
	 * Implementation for delegation by DLFSProvider
	 * @return
	 */
	public abstract DLDirectoryStream newDirectoryStream(DLPath dir, Filter<? super Path> filter);

	public DLFSFileAttributes getFileAttributes(DLPath path) {
		AVector<ACell> node=getNode(path);
		return DLFSFileAttributes.create(node);
	}

	protected abstract AVector<ACell> getNode(DLPath path);

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

}
