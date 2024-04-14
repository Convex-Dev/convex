package convex.dlfs;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Vectors;

/**
 * Static utility class for working with DLFS Node structures
 */
public class DLFSNode {

	static final AHashMap<AString,AVector<ACell>> EMPTY_CONTENTS = Maps.empty();
	static final AHashMap<AString,AVector<ACell>> NIL_CONTENTS = null;
	public static final AVector<ACell> EMPTY_DIRECTORY=Vectors.of(EMPTY_CONTENTS);
	public static final AVector<ACell> EMPTY_FILE=Vectors.of(NIL_CONTENTS);
	
	// node structure contents
	private static final long NODE_LENGTH = 1;
	private static final int POS_DIR = 0;
	
	public static boolean isDirectory(AVector<ACell> node) {
		if (node==null) return false;
		return node.get(POS_DIR)!=null;
	}

	/**
	 * Navigate down a path relative to a DLFS Node.
	 * @param node
	 * @param path
	 * @return Found node, or null if doesn't exist
	 */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> navigate(AVector<ACell> node, DLPath path) {
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
	 * @return
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

}
