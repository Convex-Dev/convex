package convex.lattice.fs;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.MapEntry;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.util.MergeFunction;
import convex.core.util.Utils;

/**
 * Static utility class for working with DLFS Node structures
 */
public class DLFSNode {
	
	// node structure contents
	/** Minimum node length. A directory node may carry an optional 5th element (POS_TOMBS). */
	public static final long NODE_LENGTH = 4;
	public static final int POS_DIR = 0; // Directory entries as index of live child nodes
	public static final int POS_DATA = 1; // File data as a Blob
	public static final int POS_METADATA = 2; // arbitrary node metadata
	public static final int POS_UTIME = 3;
	/**
	 * Optional directory tombstone index: maps deleted child name to deletion timestamp.
	 * Present (node length 5) if and only if non-empty; absent (node length 4) otherwise.
	 */
	public static final int POS_TOMBS = 4;

	static final Index<AString,AVector<ACell>> EMPTY_CONTENTS = Index.none();
	static final Index<AString,AVector<ACell>> NIL_CONTENTS = null;
	static final Blob NIL_DATA = null;
	static final Blob EMPTY_DATA = Blob.EMPTY;
	static final ACell EMPTY_METADATA = null;
	static final CVMLong EMPTY_TIME = CVMLong.ZERO;
	static final Index<AString,CVMLong> EMPTY_TOMBS = Index.none();

	private static final AVector<ACell> EMPTY_DIRECTORY=Vectors.of(EMPTY_CONTENTS,NIL_DATA,EMPTY_METADATA,EMPTY_TIME);
	private static final AVector<ACell> EMPTY_FILE=Vectors.of(NIL_CONTENTS,EMPTY_DATA,EMPTY_METADATA,EMPTY_TIME);


	
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
			Index<AString,AVector<ACell>> dir=(Index<AString, AVector<ACell>>) node.get(POS_DIR);
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
	public static Index<AString,AVector<ACell>> getDirectoryEntries(AVector<ACell> dirNode) {
		if ((dirNode==null)||(dirNode.count()<NODE_LENGTH)) return null;
		return (Index<AString, AVector<ACell>>) dirNode.get(POS_DIR);
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
		Index<AString, AVector<ACell>> entries = getDirectoryEntries(rootNode);
		if (n==1) {
			Index<AString, CVMLong> tombs = getTombstones(rootNode);
			if (newNode==null) {
				// Internal removal of a live entry (no tombstone recorded)
				entries=entries.dissoc(name);
			} else {
				// Create or modify: name becomes live, clearing any tombstone for it
				entries=entries.assoc(name, newNode);
				tombs=tombs.dissoc(name);
			}
			return withDir(rootNode, entries, tombs, utime);
		}

		AVector<ACell> childNode=entries.get(name);
		childNode=updateNode(childNode,path.subpath(1),newNode,utime);
		if (childNode==null) return null; // failed: an ancestor on the path was not a directory
		entries=entries.assoc(name, childNode);
		return withDir(rootNode, entries, getTombstones(rootNode), utime);
	}

	/**
	 * Delete the node at the given path, recording a tombstone (deleted child name to deletion
	 * timestamp) in its parent directory. The deleted child becomes absent from the live entries.
	 *
	 * @param rootNode Root node of the (sub)filesystem
	 * @param path Path of the node to delete, relative to rootNode
	 * @param utime Deletion timestamp
	 * @return Updated root node, or the original node unchanged if the path was not a live node
	 */
	public static AVector<ACell> deleteNode(AVector<ACell> rootNode, DLPath path, CVMLong utime) {
		int n=path.getNameCount();
		if (n==0) return rootNode; // cannot tombstone the root itself
		if (!isDirectory(rootNode)) return rootNode;
		AString name=path.getCVMName(0);
		Index<AString, AVector<ACell>> entries = getDirectoryEntries(rootNode);
		if (n==1) {
			if (entries.get(name)==null) return rootNode; // not a live child
			entries=entries.dissoc(name);
			Index<AString, CVMLong> tombs = getTombstones(rootNode).assoc(name, utime);
			return withDir(rootNode, entries, tombs, utime);
		}
		AVector<ACell> childNode=entries.get(name);
		if (childNode==null) return rootNode; // path does not exist
		AVector<ACell> newChild=deleteNode(childNode, path.subpath(1), utime);
		if (newChild==childNode) return rootNode; // nothing changed
		entries=entries.assoc(name, newChild);
		return withDir(rootNode, entries, getTombstones(rootNode), utime);
	}

	/**
	 * Gets the tombstone index (deleted child name to deletion timestamp) for a directory node.
	 * Returns the empty index if the node has no tombstones (length 4) or is null.
	 * @param node Directory node
	 * @return Tombstone index, never null
	 */
	@SuppressWarnings("unchecked")
	public static Index<AString, CVMLong> getTombstones(AVector<ACell> node) {
		if (node==null || node.count()<=POS_TOMBS) return EMPTY_TOMBS;
		return (Index<AString, CVMLong>) node.get(POS_TOMBS);
	}

	/**
	 * Rebuilds a directory node with the given live entries, tombstones and timestamp, maintaining
	 * the canonical invariant that the POS_TOMBS element is present if and only if it is non-empty.
	 */
	static AVector<ACell> withDir(AVector<ACell> node, Index<AString, AVector<ACell>> entries, Index<AString, CVMLong> tombs, CVMLong utime) {
		AVector<ACell> result=node;
		result=result.assoc(POS_DIR, entries);
		result=result.assoc(POS_UTIME, utime);
		boolean has5 = node.count()>POS_TOMBS;
		if (tombs==null || tombs.isEmpty()) {
			if (has5) result=result.slice(0, POS_TOMBS); // drop empty tombstone field (5 -> 4)
		} else if (has5) {
			result=result.assoc(POS_TOMBS, tombs);
		} else {
			result=result.conj(tombs); // append tombstone field (4 -> 5)
		}
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
		Index<AString, AVector<ACell>> entries = getDirectoryEntries(node);
		if (entries==null) return null;
		MapEntry<AString, AVector<ACell>> entry = entries.getEntry(name);
		return entry;
	}

	/**
	 * Returns true iff the directory node has no live entries.
	 *
	 * <p>Deleted children are recorded as tombstones in the separate {@link #POS_TOMBS}
	 * index rather than in the live entries, so emptiness is simply emptiness of the
	 * live entries.</p>
	 *
	 * @param dirNode Node assumed to be a directory
	 * @return true if {@code dirNode} is a directory with no live children (or not a directory)
	 */
	public static boolean isEmpty(AVector<ACell> dirNode) {
		Index<AString, AVector<ACell>> entries = getDirectoryEntries(dirNode);
		return entries == null || entries.isEmpty();
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
	 *
	 * The merge is deterministic: the merged node's timestamp is the maximum of the input nodes' timestamps.
	 *
	 * @param a First node (non-null). Favoured in result if all else equal.
	 * @param b Second node (non-null)
	 * @return Merged node
	 */
	public static AVector<ACell> merge(AVector<ACell> a, AVector<ACell> b) {
		if (a.equals(b)) return a;
		CVMLong timeA=getUTime(a);
		CVMLong timeB=getUTime(b);

		// Deterministic merge time: max of input node timestamps
		CVMLong mergeTime = timeA.longValue() >= timeB.longValue() ? timeA : timeB;

		Index<AString, AVector<ACell>> contA = getDirectoryEntries(a);
		Index<AString, AVector<ACell>> contB = getDirectoryEntries(b);

		if ((contA!=null)&&(contB!=null)) {
			// Two directories: merge live entries and tombstones, then reconcile conflicts
			Index<AString, CVMLong> tombA = getTombstones(a);
			Index<AString, CVMLong> tombB = getTombstones(b);

			// Fast path: identical content except timestamp, take the most recent value
			if (Utils.equals(contA, contB) && Utils.equals(tombA, tombB)) {
				return timeA.compareTo(timeB)>=0?a:b;
			}

			final java.util.HashSet<AString> touched = new java.util.HashSet<>();
			Index<AString, AVector<ACell>> mergedDir = contA.mergeDifferences(contB, new MergeFunction<AVector<ACell>>() {
				@Override
				public AVector<ACell> merge(AVector<ACell> ca, AVector<ACell> cb) {
					if (cb==null) return ca;
					if (ca==null) return cb;
					return DLFSNode.merge(ca,cb);
				}
				@Override
				public AVector<ACell> merge(Object key, AVector<ACell> ca, AVector<ACell> cb) {
					touched.add((AString)key);
					return merge(ca,cb);
				}
			});
			Index<AString, CVMLong> mergedTomb = tombA.mergeDifferences(tombB, new MergeFunction<CVMLong>() {
				@Override
				public CVMLong merge(CVMLong ta, CVMLong tb) {
					if (ta==null) return tb;
					if (tb==null) return ta;
					return ta.longValue()>=tb.longValue()?ta:tb;
				}
				@Override
				public CVMLong merge(Object key, CVMLong ta, CVMLong tb) {
					touched.add((AString)key);
					return merge(ta,tb);
				}
			});

			// Reconcile names that ended up both live and tombstoned (create-vs-delete conflicts).
			// Only names touched by the merges above can conflict, so this is divergence-proportional.
			for (AString name : touched) {
				AVector<ACell> live = mergedDir.get(name);
				if (live==null) continue;
				CVMLong death = mergedTomb.get(name);
				if (death==null) continue;
				if (death.longValue() >= getUTime(live).longValue()) {
					mergedDir = mergedDir.dissoc(name);   // deletion wins (tombstone preferred on tie)
				} else {
					mergedTomb = mergedTomb.dissoc(name);  // newer create/modify wins
				}
			}

			// Return a unchanged if it already subsumes b (no allocation)
			if ((mergedDir==contA)&&(mergedTomb==tombA)&&(timeA.longValue()>=timeB.longValue())) return a;
			return buildDirectory(mergeTime, mergedDir, mergedTomb);
		}

		// At least one node is a file: equal content keeps the most recent; otherwise newer wins
		if (Utils.equals(contA, contB) && Utils.equals(getData(a), getData(b))) {
			return timeA.compareTo(timeB)>=0?a:b;
		}
		return timeA.longValue()>=timeB.longValue()?a:b;
	}

	/**
	 * Builds a canonical directory node from merged live entries and tombstones, with the
	 * POS_TOMBS element present if and only if the tombstone index is non-empty.
	 */
	static AVector<ACell> buildDirectory(CVMLong utime, Index<AString, AVector<ACell>> entries, Index<AString, CVMLong> tombs) {
		AVector<ACell> result=createDirectory(utime);
		result=result.assoc(POS_DIR, entries);
		if (tombs!=null && !tombs.isEmpty()) {
			result=result.conj(tombs);
		}
		return result;
	}

}
