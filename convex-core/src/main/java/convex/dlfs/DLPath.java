package convex.dlfs;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchEvent.Modifier;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

import convex.core.util.Utils;

/**
 * A DLFS Path
 * 
 * Path components are Strings, separated by "/"
 */
final class DLPath implements Path {

	protected final DLFileSystem fileSystem;
	protected final String[] names;
	protected final boolean absolute;
	protected final int count;
	
	private String pathString=null;
	
	static final String DOT=".";
	static final String SLASHDOT="/.";
	static final String DOTDOT="..";

	protected DLPath(DLFileSystem fs) {
		this(fs,Utils.EMPTY_STRINGS,false);
	}
	
	protected DLPath(DLFileSystem fs,String[] names, boolean absolute) {
		this.fileSystem=fs;
		this.names=names;
		this.absolute=absolute;
		this.count=names.length;
	}

	static Path createRoot(DLFileSystem fileSystem) {
		return new DLPath(fileSystem,Utils.EMPTY_STRINGS,true);
	}
	
	static final Pattern endSlashes = Pattern.compile("/+$");
	
	static Path create(DLFileSystem fs, String path) {
		String sep=DLFileSystem.SEP;
		if (path.isEmpty()) throw new InvalidPathException(path,"Empty path name");
		
		boolean absolute=false;
		{
			int ix=0;
			while ((ix<path.length())&&(path.charAt(ix)=='/')) {
				ix++;
			}
			if (ix>0) {
				absolute=true;
				path=path.substring(ix);
			}
		}
		
		// From POSIX 4.11 A pathname that contains at least one non-slash character and that ends with one or more trailing slashes shall be resolved as if a single dot character ( '.' ) were appended to the pathname.
		if (path.endsWith(sep)) {
			path=endSlashes.matcher(path).replaceAll("/.");
		}

		String[] names=path.isEmpty()?Utils.EMPTY_STRINGS:path.split(sep);
		return new DLPath(fs,names,absolute);
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
		if (!(other instanceof DLPath)) return false;
		return endsWith((DLPath)other);
	}
	
	public boolean endWith(DLPath other) {
		if (absolute!=other.absolute) return false;
		int n=other.getNameCount();
		if (n>count) return false; // can't start with a longer path!
		if (!fileSystem.equals(other.fileSystem)) return false; 
		return Utils.arrayEquals(names,other.names,n);
	}

	@Override
	public Path normalize() {
		if (count==0) return this;  // nothing to normalize
		
		int j=0; // new names
		String[] dest=names;
		for (int i=0; i<count; i++) {
			String c=names[i];
			int strategy; // -1 = delete previous, 0 = skip, 1 = keep 
			if (DOT.equals(c)) {
				// skip over this (don't increment j)
				strategy=0;
			} else if (DOTDOT.equals(c)){
				if (absolute) {
					// we always back up in absolute paths, delete previous iff it exists
					strategy=(j>0)?-1:0; 
				} else {
					// we delete previous if it wasn't .. itself, otherwise keep
					strategy=((j>0)&&(!DOTDOT.equals(dest[j-1])))?-1:1;
				}
			} else {
				// copy element across iff we have dest, increment j always
				strategy=1;
			}
			switch (strategy) {
				case -1: if (dest==names) dest=names.clone(); if (j>0) j--; break;
				case 0: if (dest==names) dest=names.clone(); break; // skip by not incrementing j
				case 1: if (dest!=names) dest[j]=c; j++; break; // copy across
			}
		}
		if (j==count) return this; // nothing changed
		if (dest.length>j) dest=Arrays.copyOfRange(dest,0,j); // trim to length j if needed
		return new DLPath(fileSystem,dest,absolute);
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
		String joined=String.join("/", names);
		if (absolute) joined="/"+joined;
		
		// special case for empty path
		if (joined.isEmpty()) joined=DOT;

		pathString=joined;
		return joined;
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
