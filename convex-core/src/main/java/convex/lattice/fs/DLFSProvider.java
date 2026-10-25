package convex.lattice.fs;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.AtomicMoveNotSupportedException;
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
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;

import convex.core.util.SoftCache;
import convex.lattice.fs.impl.DLDirectoryStream;
import convex.lattice.fs.impl.DLFSLocal;

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
		if (fs==null || !fs.isOpen()) fs=newFileSystem(uri,null);
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
		DLPath src=checkProviderPath(source);
		DLPath dst=checkProviderPath(target);
		DLFileSystem fs=src.getFileSystem();
		Objects.requireNonNull(options,"Copy options");
		boolean recursive=false;
		boolean replaceExisting=false;
		boolean copyAttributes=false;
		for (CopyOption option:options) {
			Objects.requireNonNull(option,"Copy option");
			if (option==DLFSOption.RECURSIVE) {
				recursive=true;
			} else if (option==StandardCopyOption.REPLACE_EXISTING) {
				replaceExisting=true;
			} else if (option==StandardCopyOption.COPY_ATTRIBUTES) {
				copyAttributes=true;
			} else if (option==LinkOption.NOFOLLOW_LINKS) {
				// DLFS has no symbolic links.
			} else {
				throw new UnsupportedOperationException("Unsupported DLFS copy option: "+option);
			}
		}
		fs.copy(src,dst,recursive,replaceExisting,copyAttributes);
	}

	@Override
	public void move(Path source, Path target, CopyOption... options) throws IOException {
		DLPath src=checkProviderPath(source);
		DLPath dst=checkProviderPath(target);
		DLFileSystem fs=src.getFileSystem();
		Objects.requireNonNull(options,"Move options");
		boolean replaceExisting=false;
		for (CopyOption option:options) {
			Objects.requireNonNull(option,"Move option");
			if (option==StandardCopyOption.REPLACE_EXISTING) {
				replaceExisting=true;
			} else if (option==StandardCopyOption.ATOMIC_MOVE) {
				if (!fs.isSameDrive(dst.getFileSystem())) {
					throw new AtomicMoveNotSupportedException(src.toString(),dst.toString(),"Independent DLFS drives");
				}
			} else if (option!=DLFSOption.RECURSIVE) {
				throw new UnsupportedOperationException("Unsupported DLFS move option: "+option);
			}
		}
		fs.move(src,dst,replaceExisting);
	}

	private DLPath checkProviderPath(Path path) {
		DLPath result=DLFS.checkPath(Objects.requireNonNull(path));
		if (result.getFileSystem().provider()!=this) throw new ProviderMismatchException("Different DLFS provider");
		result.getFileSystem().checkOpen();
		return result;
	}

	@Override
	public boolean isSameFile(Path path, Path path2) throws IOException {
		DLPath first=checkProviderPath(path);
		if (!(path2 instanceof DLPath second) || second.getFileSystem().provider()!=this) return false;
		second.getFileSystem().checkOpen();
		if (first.equals(second)) return true;
		first.getFileSystem().checkAccess(first);
		second.getFileSystem().checkAccess(second);
		return first.getFileSystem().isSameDrive(second.getFileSystem())
			&& first.toAbsolutePath().normalize().toString().equals(second.toAbsolutePath().normalize().toString());
	}

	@Override
	public boolean isHidden(Path path) throws IOException {
		checkProviderPath(path);
		return false;
	}

	@Override
	public FileStore getFileStore(Path path) throws IOException {
		throw new UnsupportedOperationException("DLFS file stores are not implemented");
	}

	@Override
	public void checkAccess(Path path, AccessMode... modes) throws IOException {
		DLPath dlp=DLFS.checkPath(path);
        dlp.getFileSystem().checkAccess(dlp);
	}

	@Override
	public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
		DLPath file=checkProviderPath(path).toAbsolutePath().normalize();
		checkLinkOptions(options);
		Objects.requireNonNull(type);
		if (type==BasicFileAttributeView.class) return type.cast(new BasicFileAttributeView() {
			@Override public String name() { return "basic"; }
			@Override public BasicFileAttributes readAttributes() throws IOException {
				return file.getFileSystem().getFileAttributes(file);
			}
			@Override public void setTimes(FileTime modified,FileTime accessed,FileTime created) throws IOException {
				file.getFileSystem().setTimes(file,modified,accessed,created);
			}
		});
		return null;
	}

	private static void checkLinkOptions(LinkOption[] options) {
		for (LinkOption option:Objects.requireNonNull(options)) Objects.requireNonNull(option);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options)
			throws IOException {
		Objects.requireNonNull(type);
		checkLinkOptions(options);
		if (type==BasicFileAttributes.class) {
			DLPath dlp=checkProviderPath(path).toAbsolutePath().normalize();
			return (A) dlp.getFileSystem().getFileAttributes(dlp);
		} else {
			throw new UnsupportedOperationException("No support of attributes type: "+type);
		}
	}

	@Override
	public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
		String names=basicAttributeNames(attributes);
		BasicFileAttributes a=readAttributes(path,BasicFileAttributes.class,options);
		Map<String,Object> result=new LinkedHashMap<>();
		for (String name:names.split(",",-1)) {
			if (name.equals("*")) {
				for (String key:BASIC_ATTRIBUTE_NAMES) result.put(key,basicAttribute(a,key));
			} else result.put(name,basicAttribute(a,name));
		}
		return result;
	}

	private static final String[] BASIC_ATTRIBUTE_NAMES={"lastModifiedTime","lastAccessTime","creationTime",
		"size","isRegularFile","isDirectory","isSymbolicLink","isOther","fileKey"};

	private static Object basicAttribute(BasicFileAttributes a,String name) {
		return switch (name) {
		case "lastModifiedTime" -> a.lastModifiedTime();
		case "lastAccessTime" -> a.lastAccessTime();
		case "creationTime" -> a.creationTime();
		case "size" -> a.size();
		case "isRegularFile" -> a.isRegularFile();
		case "isDirectory" -> a.isDirectory();
		case "isSymbolicLink" -> a.isSymbolicLink();
		case "isOther" -> a.isOther();
		case "fileKey" -> a.fileKey();
		default -> throw new IllegalArgumentException("Unknown basic attribute: "+name);
		};
	}

	@Override
	public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
		String name=basicAttributeNames(attribute);
		BasicFileAttributeView view=getFileAttributeView(path,BasicFileAttributeView.class,options);
		switch (name) {
		case "lastModifiedTime" -> view.setTimes((FileTime)Objects.requireNonNull(value),null,null);
		case "lastAccessTime" -> view.setTimes(null,(FileTime)Objects.requireNonNull(value),null);
		case "creationTime" -> view.setTimes(null,null,(FileTime)Objects.requireNonNull(value));
		default -> throw new IllegalArgumentException("Attribute is unknown or read-only: "+name);
		}
	}

	private static String basicAttributeNames(String attributes) {
		int colon=attributes.indexOf(':');
		if (colon<0) return attributes;
		if (!attributes.substring(0,colon).equals("basic")) throw new UnsupportedOperationException("Unsupported attribute view: "+attributes);
		return attributes.substring(colon+1);
	}

}
