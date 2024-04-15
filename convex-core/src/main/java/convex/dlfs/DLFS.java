package convex.dlfs;

import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;

import convex.core.data.AString;
import convex.core.data.Strings;
import convex.dlfs.impl.DLFSLocal;

/**
 * Static API for DLFS
 */
public class DLFS {

	private static final DLFSProvider PROVIDER=new DLFSProvider();
	
	/**
	 * URI scheme for DLFS
	 */
	public static final String SCHEME = "dlfs";

	public static final String ROOT_STRING = "/";
	
	public static DLFSProvider provider() {
		return PROVIDER;
	}
	
	public static DLFileSystem createLocal() {
		return DLFSLocal.create(provider());
	}

	/**
	 * Converts to a DLFS path
	 * @param path Path to check
	 * @return DLFS compatible Path instance
	 * @throws ProviderMismatchException if not a DLFS file
	 */
	public static DLPath checkPath(Path path) {
		if (path instanceof DLPath) return (DLPath) path;
		throw new ProviderMismatchException("Not a DLFS path");
	}

	public static AString checkName(String name) {
		if (name==null) return null;
		if (name.isEmpty()) return null;
		return Strings.create(name);
	}

	public static AString checkName(AString name) {
		if (name==null) return null;
		if (name.isEmpty()) return null;
		return name;
	}

}
