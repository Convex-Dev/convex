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
	
	public static final AVector<ACell> EMPTY_DIRECTORY=Vectors.of(EMPTY_CONTENTS,NIL_DATA,EMPTY_METADATA,EMPTY_TIME);
	public static final AVector<ACell> EMPTY_FILE=Vectors.of(NIL_CONTENTS,EMPTY_DATA,EMPTY_METADATA,EMPTY_TIME);
	public static final AVector<ACell> TOMBSTONE=Vectors.of(NIL_CONTENTS,NIL_DATA,EMPTY_METADATA,EMPTY_TIME);
	
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
	 * @param node
	 * @param path
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
	 * @param dirNode
	 * @return Map of directory entries, or null if not a directory
	 */
	@SuppressWarnings("unchecked")
	public static AHashMap<AString,AVector<ACell>> getDirectoryEntries(AVector<ACell> dirNode) {
		if ((dirNode==null)||(dirNode.count()<NODE_LENGTH)) return null;
		return (AHashMap<AString, AVector<ACell>>) dirNode.get(POS_DIR);
	}

	/**
	 * Update a node at a path relative to a root node
	 * @param rootNode
	 * @param path Path relative to root
	 * @param newNode New node, or null to delete a node
	 * @return Updated root node, or null if update failed (parent(s) not a directory)
	 */
	public static AVector<ACell> updateNode(AVector<ACell> rootNode, DLPath path,AVector<ACell> newNode) {
		int n=path.getNameCount();
		if (n==0) return newNode;
		
		if (!isDirectory(rootNode)) return null;
		
		AString name=path.getCVMName(0);
		AHashMap<AString, AVector<ACell>> entries = getDirectoryEntries(rootNode);
		AVector<ACell> childNode=entries.get(name);
		
		childNode=updateNode(childNode,path.subpath(1),newNode);
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
		return rootNode.assoc(POS_DIR, entries);
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

}
