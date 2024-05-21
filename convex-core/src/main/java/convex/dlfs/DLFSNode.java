package convex.dlfs;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.util.MergeFunction;
import convex.core.util.Utils;

/**
 * Static utility class for working with DLFS Node structures
 */
public class DLFSNode {

	static final AHashMap<AString,AVector<ACell>> EMPTY_CONTENTS = Maps.empty();
	static final AHashMap<AString,AVector<ACell>> NIL_CONTENTS = null;
	static final Blob NIL_DATA = null;
	static final Blob EMPTY_DATA = Blob.EMPTY;
	static final ACell EMPTY_METADATA = null;
	static final CVMLong EMPTY_TIME = CVMLong.ZERO;
	
	private static final AVector<ACell> EMPTY_DIRECTORY=Vectors.of(EMPTY_CONTENTS,NIL_DATA,EMPTY_METADATA,EMPTY_TIME);
	private static final AVector<ACell> EMPTY_FILE=Vectors.of(NIL_CONTENTS,EMPTY_DATA,EMPTY_METADATA,EMPTY_TIME);
	private static final AVector<ACell> TOMBSTONE=Vectors.of(NIL_CONTENTS,NIL_DATA,EMPTY_METADATA,EMPTY_TIME);
	
	// node structure contents
	public static final long NODE_LENGTH = 4;
	public static final int POS_DIR = 0;
	public static final int POS_DATA = 1;
	public static final int POS_METADATA = 2;
	public static final int POS_UTIME = 3;
	
	public static boolean isDirectory(AVector<ACell> node) {
		if (node==null) return false;
		return node.get(POS_DIR)!=null;
	}
	
	public static boolean isRegularFile(AVector<ACell> node) {
		if (node==null) return false;
		return node.get(POS_DATA) instanceof ABlob;
	}

	/**
	 * Navigate down a path relative to a DLFS Node.
	 * @param node Node from which to navigate
	 * @param path Path to navigate from (assumed to be relative)
	 * @return Found node, or null if doesn't exist
	 */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> navigate(AVector<ACell> node, DLPath path) {
		if (path==null) return null;
		int n=path.getNameCount();
		for (int i=0; i<n; i++) {
			AString compName=path.getCVMName(i);
			AHashMap<AString,AVector<ACell>> dir=(AHashMap<AString, AVector<ACell>>) node.get(POS_DIR);
			if (dir==null) return null;
			AVector<ACell> child=dir.get(compName);
			if (child==null) return null;
			node=child;
		}
		return node;
	}

	/**
	 * Gets the directory entries for a node
	 * @param dirNode Node which is assumed to be a directory
	 * @return Map of directory entries, or null if not a directory
	 */
	@SuppressWarnings("unchecked")
	public static AHashMap<AString,AVector<ACell>> getDirectoryEntries(AVector<ACell> dirNode) {
		if ((dirNode==null)||(dirNode.count()<NODE_LENGTH)) return null;
		return (AHashMap<AString, AVector<ACell>>) dirNode.get(POS_DIR);
	}

	/**
	 * Update a node at a path relative to a root node
	 * @param rootNode Root node of file system
	 * @param path Path relative to root
	 * @param newNode New node, or null to delete a node
	 * @param utime Timestamp to set on any directories changed
 	 * @return Updated root node, or null if update failed (parent(s) not a directory)
	 */
	public static AVector<ACell> updateNode(AVector<ACell> rootNode, DLPath path,AVector<ACell> newNode, CVMLong utime) {
		int n=path.getNameCount();
		if (n==0) return newNode;
		
		if (!isDirectory(rootNode)) return null;
		
		AString name=path.getCVMName(0);
		AHashMap<AString, AVector<ACell>> entries = getDirectoryEntries(rootNode);
		AVector<ACell> childNode=entries.get(name);
		
		childNode=updateNode(childNode,path.subpath(1),newNode,utime);
		if (childNode==null) {
			if (n==1) {
				// deleting an entry at this position
				entries=entries.dissoc(name);		
			} else {
				// we failed
				return null;
			}
		} else {
			// we have an updated child
			entries=entries.assoc(name, childNode);
		}
		AVector<ACell> result=rootNode;
		result=result.assoc(POS_DIR, entries);
		result=result.assoc(POS_UTIME, utime);
		return result;
	}
	
	/**
	 * Gets the data from a DLFS file node, or nil if not a regular File
	 */
	public static ABlob getData(AVector<ACell> node) {
		return (ABlob) node.get(POS_DATA);
	}
	
	/**
	 * Gets the metadata from a DLFS node
	 */
	public static Blob getMetaData(AVector<ACell> node) {
		return (Blob) node.get(POS_METADATA);
	}
	
	/**
	 * Gets the metadata from a DLFS node
	 */
	public static CVMLong getUTime(AVector<ACell> node) {
		return (CVMLong) node.get(POS_UTIME);
	}

	/**
	 * 
	 * @param node Node to check for directory
	 * @param name Directory entry name
	 * @return Directory entry, return null if not found or node is not a directory
	 */
	public static MapEntry<AString, AVector<ACell>> getDirectoryEntry(AVector<ACell> node, AString name) {
		AHashMap<AString, AVector<ACell>> entries = getDirectoryEntries(node);
		if (entries==null) return null;
		MapEntry<AString, AVector<ACell>> entry = entries.getEntry(name);
		return entry;
	}

	/**
	 * Returns true iff the node is a DLFS tombstone
	 * @param node Node to test
	 * @return True if a tombstone, false if anything else (including null)
	 */
	public static boolean isTombstone(AVector<ACell> node) {
		if (node==null) return false;
		return (!isDirectory(node)&&!isRegularFile(node));
	}

	private static AVector<ACell> lastTombstone=TOMBSTONE;
	public static AVector<ACell> createTombstone(CVMLong timestamp) {
		AVector<ACell> last=lastTombstone;
		last= TOMBSTONE.assoc(POS_UTIME,timestamp);
		lastTombstone=last;
		return last;
	}

	private static AVector<ACell> lastDirectory=EMPTY_DIRECTORY;
	public static AVector<ACell> createDirectory(CVMLong timestamp) {
		AVector<ACell> last=lastDirectory;
		last= EMPTY_DIRECTORY.assoc(POS_UTIME,timestamp);
		lastDirectory=last;
		return last;
	}
	
	private static AVector<ACell> lastEmptyFile=EMPTY_FILE;
	public static AVector<ACell> createEmptyFile(CVMLong timestamp) {
		AVector<ACell> last=lastEmptyFile;
		last= EMPTY_FILE.assoc(POS_UTIME,timestamp);
		lastEmptyFile=last;
		return last;
	}

	/**
	 * Merges two DLFS nodes recursively. Favours newer (utime) entries in case of conflicts.
	 * @param a First node (non-null). Favoured in result if all else equal.
	 * @param b Second node (non-null)
	 * @param time Update time for merged changes
	 * @return Merged node
	 */
	public static AVector<ACell> merge(AVector<ACell> a, AVector<ACell> b, CVMLong time) {
		if (a.equals(b)) return a;
		CVMLong timeA=getUTime(a);
		CVMLong timeB=getUTime(b);
		
		AHashMap<AString, AVector<ACell>> contA = getDirectoryEntries(a);
		AHashMap<AString, AVector<ACell>> contB = getDirectoryEntries(b);
		
		// might be equal in all content except timestamp, if so take the most recent value.
		if (Utils.equals(contA, contB)) {
			if (Utils.equals(getData(a), getData(b))) {
				return timeA.compareTo(timeB)>=0?a:b;
			}
		}
		
		if ((contA!=null)&&(contB!=null)) {
			// we have two directories, so need to merge by entry name
			AHashMap<AString, AVector<ACell>> mergedEntries=contA.mergeDifferences(contB, new MergeFunction<AVector<ACell>>() {
				@Override
				public AVector<ACell> merge(AVector<ACell> ca, AVector<ACell> cb) {
					// We know values are different at this point
					
					// nulls mean other map has a missing value
					if (cb==null) return ca;
					if (ca==null) return cb;
					
					return DLFSNode.merge(ca,cb,time);
				}
			});
			
			// Helps performance a lot if we can return a directly with no changes
			if ((contA==mergedEntries)&&(timeA.longValue()>=time.longValue())) return a; 
			
			AVector<ACell> result=createDirectory(time);
			result=result.assoc(POS_DIR, mergedEntries);
			return result;
		} else {
			// at least one in not a directory, so select based on more recent timestamp, or choose a if equal
			AVector<ACell> result= timeA.longValue()>=timeB.longValue()?a:b;
			return result;
		}
	}

}
