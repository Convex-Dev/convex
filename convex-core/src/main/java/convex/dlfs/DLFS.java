package convex.dlfs;

/**
 * Static API for DLFS
 */
public class DLFS {

	private static final DLFSProvider PROVIDER=new DLFSProvider();
	
	public static DLFSProvider provider() {
		return PROVIDER;
	}

}
