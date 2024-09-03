package convex.dlfs;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import convex.core.util.SoftCache;
import convex.dlfs.impl.DLDirectoryStream;
import convex.dlfs.impl.DLFSLocal;

/**
 * DLFS filesystem provider. Maintains a cache of file systems loaded on the current system.
 * 
 */
public class DLFSProvider extends FileSystemProvider {

	public SoftCache<String,DLFileSystem> fileSystems=new SoftCache<>();
	
	@Override
	public String getScheme() {
		return DLFS.SCHEME;
	}

	@Override
	public DLFileSystem newFileSystem(URI uri, Map<String, ?> env) {
		String path = uri.getPath();
       
        DLFileSystem fs= DLFSLocal.create(this); 
        fileSystems.put(path, fs);
        return fs;
	}

	@Override
	public DLFileSystem getFileSystem(URI uri) {
		String pathPart = uri.getPath();
		if (pathPart==null) throw new IllegalArgumentException("URI contains no path");
		DLFileSystem fs= fileSystems.get(pathPart);
		if (fs==null) fs=newFileSystem(uri,null);
		return fs;
	}

	public Iterable<DLFileSystem> getFileSystems() {
		ArrayList<DLFileSystem> systems=new ArrayList<>();
		for (Entry<String, DLFileSystem> e: fileSystems.entrySet()) {
			DLFileSystem fs=e.getValue();
			if (fs!=null) systems.add(fs); 
		}
		return systems;
	}
	
	@Override
	public Path getPath(URI uri) {
		return getFileSystem(uri).getPath(uri.getPath());
	}

	@Override
	public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs)
			throws IOException {
		// Note this either opens or creates a file
		FileSystem fs=path.getFileSystem();
		if (!(fs instanceof DLFileSystem)) {
            throw new ProviderMismatchException("Not DLFS");
        }
        return ((DLFileSystem) fs).newByteChannel((DLPath)path, options, attrs);
	}

	@Override
	public DirectoryStream<Path> newDirectoryStream(Path dir, Filter<? super Path> filter) throws IOException {
		FileSystem fs=dir.getFileSystem();
		if (!(fs instanceof DLFileSystem)) {
            throw new ProviderMismatchException("Not DLFS");
        }
        DLDirectoryStream stream = ((DLFileSystem) fs).newDirectoryStream((DLPath)dir, filter);
        if (stream==null) throw new NotDirectoryException("Not a directory: "+dir);
        return stream;
	}

	@Override
	public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
		FileSystem fs=dir.getFileSystem();
		if (!(fs instanceof DLFileSystem)) {
            throw new ProviderMismatchException("Not DLFS");
        }
        ((DLFileSystem) fs).createDirectory((DLPath)dir, attrs);
	}

	@Override
	public void delete(Path path) throws IOException {
		DLPath dlp=DLFS.checkPath(path);
        dlp.getFileSystem().delete(dlp);
	}

	@Override
	public void copy(Path source, Path target, CopyOption... options) throws IOException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void move(Path source, Path target, CopyOption... options) throws IOException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public boolean isSameFile(Path path, Path path2) throws IOException {
		return path.toAbsolutePath().equals(path2.toAbsolutePath());
	}

	@Override
	public boolean isHidden(Path path) throws IOException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public FileStore getFileStore(Path path) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void checkAccess(Path path, AccessMode... modes) throws IOException {
		DLPath dlp=DLFS.checkPath(path);
        dlp.getFileSystem().checkAccess(dlp);
	}

	@Override
	public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
		// We don't currently have an updatable file attribute view. OK for now?
		return null;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options)
			throws IOException {
		if (type==BasicFileAttributes.class) {
			DLPath dlp=DLFS.checkPath(path);
			return (A) dlp.getFileSystem().getFileAttributes(dlp);
		} else {
			throw new UnsupportedOperationException("No support of attributes type: "+type);
		}
	}

	@Override
	public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
		// TODO Auto-generated method stub
		
	}

}
