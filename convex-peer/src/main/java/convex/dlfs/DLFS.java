package convex.dlfs;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Collections;
import java.util.Set;

import convex.core.util.Utils;

public class DLFS extends FileSystem {

	static final String SEP = "/";

	protected DLFSProvider provider;
	
	// Singleton root / empty paths
	protected DLPath root=new DLPath(this,Utils.EMPTY_STRINGS,true);
	protected DLPath emptyPath=new DLPath(this);
	
	public DLFS(DLFSProvider dlfsProvider) {
		this.provider=dlfsProvider;
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
		// TODO Auto-generated method stub
		return null;
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
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public WatchService newWatchService() throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * Gets the unique root path for this FileSystem
	 * @return
	 */
	public Path getRoot() {
		return root;
	}
	
	/**
	 * Gets the unique root path for this FileSystem
	 * @return
	 */
	public Path getEmptyPath() {
		return emptyPath;
	}

}
