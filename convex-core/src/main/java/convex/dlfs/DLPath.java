package convex.dlfs;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchEvent.Modifier;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Arrays;
import java.util.Objects;

import convex.core.util.Utils;

final class DLPath implements Path {

	protected final DLFileSystem fileSystem;
	protected final String[] names;
	protected final boolean absolute;
	protected final int count;
	
	private String pathString=null;

	protected DLPath(DLFileSystem fs) {
		this(fs,Utils.EMPTY_STRINGS,false);
	}
	
	protected DLPath(DLFileSystem fs,String[] names, boolean absolute) {
		this(fs,names,absolute,null);
	}
	
	protected DLPath(DLFileSystem fs,String[] names, boolean absolute, String fullPath) {
		this.fileSystem=fs;
		this.names=names;
		this.absolute=absolute;
		this.count=names.length;
		this.pathString=fullPath;
	}

	static Path createRoot(DLFileSystem fileSystem) {
		return new DLPath(fileSystem,Utils.EMPTY_STRINGS,true);
	}
	
	static Path create(DLFileSystem fs, String fullPath) {
		String sep=fs.getSeparator();
		boolean absolute=fullPath.startsWith(sep);
		String comps;
		if (absolute) {
			comps=fullPath.substring(sep.length());
		} else {
			comps=fullPath;
		}
		String[] names=comps.split(sep);
		return new DLPath(fs,names,absolute,fullPath);
	}
	
	@Override
	public FileSystem getFileSystem() {
		return fileSystem;
	}

	@Override
	public boolean isAbsolute() {
		return absolute;
	}

	@Override
	public Path getRoot() {
		if (!absolute) return null; 
		return fileSystem.getRoot();
	}

	@Override
	public Path getFileName() {
		if (count==0) return null;
		if (!absolute&(count==1)) return this;
		return new DLPath(fileSystem,new String[] {names[count-1]},false);
	}

	@Override
	public Path getParent() {
		int n=getNameCount();
		if (n==0) return null;
		return new DLPath(fileSystem,Arrays.copyOf(names, n-1),absolute);
	}

	@Override
	public int getNameCount() {
		return count;
	}

	@Override
	public Path getName(int index) {
		int n=getNameCount();
		if ((index<0)||(index>=n)) throw new IllegalArgumentException("index out of range");
		if (!absolute&(count==1)) return this;
		return new DLPath(fileSystem,new String[] {names[index]},false);
	}

	@Override
	public Path subpath(int beginIndex, int endIndex) {
		int n=getNameCount();
		if ((beginIndex<0)||(endIndex>=n)) throw new IllegalArgumentException("index out of range");
		if (beginIndex>endIndex) throw new IllegalArgumentException("negative length");
		if (!absolute&&(beginIndex==0)&&(endIndex==n)) return this;
		return new DLPath(fileSystem,Arrays.copyOfRange(names, beginIndex, endIndex),false);
	}

	@Override
	public boolean startsWith(Path other) {
		if (!(other instanceof DLPath)) return false;
		
		return startsWith((DLPath)other);
	}
	
	public boolean startsWith(DLPath other) {
		if (absolute!=other.absolute) return false;
		int n=other.getNameCount();
		if (n>count) return false; // can't start with a longer path!
		if (!fileSystem.equals(other.fileSystem)) return false;
		 
		return Utils.arrayEquals(names,other.names,n);
	}

	@Override
	public boolean endsWith(Path other) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public Path normalize() {
		return this;
	}

	@Override
	public Path resolve(Path other) {
		if (other.isAbsolute()) return other;
		if (other.getNameCount()==0) return this;
		String[] newNames=Utils.concat(names, extractNames(other));
		return new DLPath(fileSystem,newNames,absolute);
	}

	private static String[] extractNames(Path other) {
		if (other instanceof DLPath) {
			return ((DLPath)other).names;
		}
		return null;
	}

	@Override
	public Path relativize(Path other) {
		if (!(other instanceof DLPath)) throw new IllegalArgumentException("Not a DLFS path");
		return relativize((DLPath) other);
	}
	
	public DLPath relativize(DLPath other) {
		// same path implies relative path is the empty path
		if (this.equals(other)) return fileSystem.getEmptyPath();
		
		// if only one is absolute, can't construct relative path
		if (absolute!=other.isAbsolute()) return null;
		
		// can't produce relative path backwards
		int extra = other.count-count;
		if (extra<0) return null;
		
		for (int i=0; i<count; i++) {
			if (Objects.equals(names[i], other.names[i])) return null;
		}
		
		String[] newNames=Arrays.copyOfRange(other.names, count, count+extra);
		return new DLPath(fileSystem,newNames,false);
	}

	@Override
	public URI toUri() {
		try {
			return new URI(fileSystem.provider.getScheme(),toString(),null);
		} catch (URISyntaxException e) {
			throw new Error("Invalid URI construction?",e);
		}
	}

	@Override
	public Path toAbsolutePath() {
		if (absolute) return this;
		return new DLPath(fileSystem,names,true);
	}

	@Override
	public Path toRealPath(LinkOption... options) throws IOException {
		if (absolute) return this;
		return toAbsolutePath();
	}

	@Override
	public WatchKey register(WatchService watcher, Kind<?>[] events, Modifier... modifiers) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public int compareTo(Path other) {
		if (!(other instanceof DLPath)) throw new IllegalArgumentException("Not a data lattice path!");
		return compareTo((DLPath)other);
	}
	
	public int compareTo(DLPath other) {
		int n1=this.getNameCount();
		int n2=other.getNameCount(); 
		int n=Math.min(n1,n2);
		for (int i=0; i<n; i++) {
			int c=names[i].compareTo(other.names[i]);
			if (c!=0) return c;
		}
		return n1-n2;
	}
	
	@Override
	public String toString() {
		if (pathString!=null) return pathString;
		String result=String.join("/", names);
		if (absolute) result="/"+result;
		pathString=result;
		return pathString;
	}
	
	@Override
	public boolean equals(Object other) {
		if (!(other instanceof DLPath)) return false;
		return equals((DLPath)other);
	}
	
	public boolean equals(DLPath other) {
		if (this==other) return true;
		if (!other.fileSystem.equals(this.fileSystem)) return false;
		if (count!=other.count) return false;
		return Arrays.equals(names,other.names);
	}
}
