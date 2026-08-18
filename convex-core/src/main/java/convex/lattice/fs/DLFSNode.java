package convex.lattice.fs;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

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
	public static final int POS_METADATA = 2; // opaque application-defined metadata
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

	/**
	 * Recursive merge of two directory child nodes (live entries). A name present on only one
	 * side is kept unchanged; names present on both are merged recursively. No instrumentation —
	 * a purely-live change can never be a tombstone conflict, so the live merge needs none.
	 */
	private static final class ChildMerge implements MergeFunction<AVector<ACell>> {
		private final long maximumTimestamp;

		private ChildMerge(long maximumTimestamp) {
			this.maximumTimestamp=maximumTimestamp;
		}

		@Override
		public AVector<ACell> merge(AVector<ACell> ca, AVector<ACell> cb) {
			if (cb==null) return ca;
			if (!isValidNodeShallow(cb) || getUTime(cb).longValue()>maximumTimestamp) {
				throw new IllegalArgumentException("Malformed or future-dated foreign DLFS child");
			}
			if (ca==null) return cb;
			if (!isValidNodeShallow(ca)) throw new IllegalArgumentException("Malformed own DLFS child");
			return DLFSNode.merge(ca,cb,this);
		}
	}

	private static final ChildMerge UNBOUNDED_CHILD_MERGE=new ChildMerge(Long.MAX_VALUE);


	
	public static boolean isDirectory(AVector<ACell> node) {
		if (node==null) return false;
		return node.get(POS_DIR) instanceof Index;
	}
	
	public static boolean isRegularFile(AVector<ACell> node) {
		if (node==null) return false;
		return node.get(POS_DATA) instanceof ABlob;
	}

	/**
	 * Performs bounded, shallow validation of a node without walking its children.
	 * Child nodes are validated lazily as paths or merge differences reach them.
	 */
	public static boolean isValidNodeShallow(AVector<ACell> node) {
		if (node == null || (node.count() != NODE_LENGTH && node.count() != NODE_LENGTH + 1)) return false;
		ACell dirs = node.get(POS_DIR);
		ACell data = node.get(POS_DATA);
		boolean directory = dirs instanceof Index;
		boolean file = data instanceof ABlob;
		if (directory == file) return false; // exactly one node kind
		if (directory && data != null) return false;
		if (file && dirs != null) return false;
		if (!(node.get(POS_UTIME) instanceof CVMLong)) return false;
		if (node.count() > POS_TOMBS) {
			ACell tombs = node.get(POS_TOMBS);
			if (!directory || !(tombs instanceof Index<?, ?> index) || index.isEmpty()) return false;
		}
		return true;
	}

	/**
	 * Validates a complete DLFS subtree without recursive Java calls.
	 *
	 * <p>This is intended for novel foreign subtrees, not the ordinary filesystem
	 * access fast path. It validates child and tombstone entry types as well as
	 * every node shape.</p>
	 *
	 * @param root root of the subtree
	 * @return true if the complete subtree is valid
	 */
	public static boolean isValidTree(AVector<ACell> root) {
		if (!isValidNodeShallow(root)) return false;
		ACell rootContents=root.get(POS_DIR);
		if (!(rootContents instanceof Index<?,?> rootEntries)) return true; // regular file
		if (rootEntries.isEmpty() && root.count()==NODE_LENGTH) return true; // common empty directory
		Deque<AVector<ACell>> pending=new ArrayDeque<>();
		Set<AVector<ACell>> seen=Collections.newSetFromMap(new IdentityHashMap<>());
		pending.push(root);
		while (!pending.isEmpty()) {
			AVector<ACell> node=pending.pop();
			if (!seen.add(node)) continue;
			if (!isValidNodeShallow(node)) return false;
			ACell contents=node.get(POS_DIR);
			if (contents instanceof Index<?,?> entries) {
				long n=entries.count();
				for (long i=0;i<n;i++) {
					MapEntry<?,?> entry=entries.entryAt(i);
					if (!(entry.getKey() instanceof AString)) return false;
					if (!(entry.getValue() instanceof AVector<?> child)) return false;
					@SuppressWarnings("unchecked")
					AVector<ACell> childNode=(AVector<ACell>)child;
					pending.push(childNode);
				}
			}
			if (node.count()>POS_TOMBS) {
				Index<?,?> tombs=(Index<?,?>)node.get(POS_TOMBS);
				long n=tombs.count();
				for (long i=0;i<n;i++) {
					MapEntry<?,?> entry=tombs.entryAt(i);
					if (!(entry.getKey() instanceof AString) || !(entry.getValue() instanceof CVMLong)) return false;
				}
			}
		}
		return true;
	}

	/**
	 * Resolves a path while using any readable directory indexes encountered.
	 * The returned value is deliberately raw so that operations such as delete
	 * can remove a malformed final entry. A malformed ancestor only fails when
	 * its directory index cannot be read.
	 *
	 * @param rootNode filesystem root
	 * @param path path to resolve
	 * @return raw stored value, or null for genuine absence
	 * @throws DLFSCorruptionException if navigation is blocked by malformed state
	 */
	public static ACell resolveRaw(ACell rootNode, DLPath path) throws DLFSCorruptionException {
		if (path==null || rootNode==null) return null;
		ACell current=rootNode;
		int n=path.getNameCount();
		for (int i=0;i<n;i++) {
			if (!(current instanceof AVector<?> vector)) {
				throw corruption(path,"Non-node value blocks path at component "+i);
			}
			@SuppressWarnings("unchecked")
			AVector<ACell> node=(AVector<ACell>)vector;
			ACell contents=(node.count()>POS_DIR)?node.get(POS_DIR):null;
			if (!(contents instanceof Index<?,?> rawEntries)) {
				if (isValidNodeShallow(node)) return null; // a regular file has no descendants
				throw corruption(path,"Unreadable directory blocks path at component "+i);
			}
			@SuppressWarnings("unchecked")
			Index<AString,ACell> entries=(Index<AString,ACell>)rawEntries;
			current=entries.get(path.getCVMName(i));
			if (current==null) return null;
		}
		return current;
	}

	/**
	 * Resolves a valid final node, allowing navigation through an otherwise
	 * malformed ancestor when its directory index is still readable.
	 *
	 * @return valid node, or null for genuine absence
	 */
	public static AVector<ACell> resolveNode(ACell rootNode, DLPath path) throws DLFSCorruptionException {
		ACell value=resolveRaw(rootNode,path);
		if (value==null) return null;
		if (!(value instanceof AVector<?> vector)) throw corruption(path,"Stored value is not a DLFS node");
		@SuppressWarnings("unchecked")
		AVector<ACell> node=(AVector<ACell>)vector;
		if (!isValidNodeShallow(node)) throw corruption(path,"Malformed DLFS node");
		return node;
	}

	/**
	 * Resolves the readable live-entry index for a directory operation. Other
	 * damaged fields do not prevent listing when the directory contents remain
	 * readable.
	 *
	 * @return raw directory Index, or null if the path is absent or a valid file
	 */
	public static Index<?,?> resolveDirectoryEntries(ACell rootNode, DLPath path) throws DLFSCorruptionException {
		ACell value=resolveRaw(rootNode,path);
		if (value==null) return null;
		if (!(value instanceof AVector<?> vector)) throw corruption(path,"Stored value is not a DLFS node");
		if (vector.count()>POS_DIR) {
			ACell contents=vector.get(POS_DIR);
			if (contents instanceof Index<?,?> entries) return entries;
		}
		@SuppressWarnings("unchecked")
		AVector<ACell> node=(AVector<ACell>)vector;
		if (isValidNodeShallow(node)) return null;
		throw corruption(path,"Directory contents are unreadable");
	}

	/**
	 * Resolves a path for mutation. Every node on the path must be valid because
	 * rebuilding a malformed ancestor could silently discard corrupt state.
	 *
	 * @return valid node, or null for genuine absence
	 */
	public static AVector<ACell> resolveValidPath(ACell rootNode, DLPath path) throws DLFSCorruptionException {
		if (path==null || rootNode==null) return null;
		ACell current=rootNode;
		int n=path.getNameCount();
		for (int i=0;i<=n;i++) {
			if (!(current instanceof AVector<?> vector)) throw corruption(path,"Stored value is not a DLFS node");
			@SuppressWarnings("unchecked")
			AVector<ACell> node=(AVector<ACell>)vector;
			if (!isValidNodeShallow(node)) throw corruption(path,"Malformed node on mutation path at component "+i);
			if (i==n) return node;
			Index<AString,AVector<ACell>> entries=getDirectoryEntries(node);
			if (entries==null) return null;
			current=getRawEntry(entries,path.getCVMName(i));
			if (current==null) return null;
		}
		throw new AssertionError();
	}

	/** Returns a raw directory entry without trusting the Index value type. */
	@SuppressWarnings("unchecked")
	public static ACell getRawEntry(Index<?,?> entries, AString name) {
		return ((Index<AString,ACell>)entries).get(name);
	}

	private static DLFSCorruptionException corruption(DLPath path, String reason) {
		return new DLFSCorruptionException(path.toString(),reason);
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
		if (!isValidNodeShallow(node)) return null;
		int n=path.getNameCount();
		for (int i=0; i<n; i++) {
			AString compName=path.getCVMName(i);
			Index<AString,AVector<ACell>> dir=getDirectoryEntries(node);
			if (dir==null) return null;
			ACell candidate=dir.get(compName);
			if (!(candidate instanceof AVector<?>)) return null;
			AVector<ACell> child=(AVector<ACell>) candidate;
			if (!isValidNodeShallow(child)) return null;
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
		ACell value=dirNode.get(POS_DIR);
		if (!(value instanceof Index<?,?>)) return null;
		return (Index<AString, AVector<ACell>>) value;
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
		ACell value=node.get(POS_TOMBS);
		if (!(value instanceof Index<?,?>)) return EMPTY_TOMBS;
		return (Index<AString, CVMLong>) value;
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
		if (node==null || node.count()<NODE_LENGTH) return null;
		ACell value=node.get(POS_DATA);
		return (value instanceof ABlob blob) ? blob : null;
	}
	
	/**
	 * Gets the opaque application-defined metadata from a DLFS node.
	 *
	 * <p>DLFS does not interpret the value. {@code null} means that no metadata is present;
	 * any other {@link ACell} value is permitted. Applications are responsible for defining
	 * and validating their own metadata schema.</p>
	 *
	 * @param node DLFS node, or {@code null}
	 * @return metadata value, or {@code null} if absent
	 */
	public static ACell getMetadata(AVector<ACell> node) {
		if (node==null || node.count()<NODE_LENGTH) return null;
		return node.get(POS_METADATA);
	}

	/**
	 * Legacy spelling for {@link #getMetadata(AVector)}.
	 *
	 * @deprecated Use {@link #getMetadata(AVector)}.
	 */
	@Deprecated(since="0.8.9", forRemoval=false)
	public static ACell getMetaData(AVector<ACell> node) {
		return getMetadata(node);
	}

	/**
	 * Returns a node with new opaque metadata and modification timestamp.
	 *
	 * <p>The timestamp is part of the metadata contract: metadata is resolved with the
	 * containing node's normal last-write-wins ordering, rather than merged separately.
	 * Passing {@code null} as metadata clears it.</p>
	 *
	 * @param node DLFS node to update
	 * @param metadata application-defined metadata, or {@code null} to clear
	 * @param utime modification timestamp
	 * @return updated immutable DLFS node
	 */
	public static AVector<ACell> withMetadata(AVector<ACell> node, ACell metadata, CVMLong utime) {
		if (!isValidNodeShallow(node)) throw new IllegalArgumentException("Invalid DLFS node");
		if (utime==null) throw new IllegalArgumentException("DLFS metadata timestamp cannot be null");
		return node.assoc(POS_METADATA, metadata).assoc(POS_UTIME, utime);
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
		return merge(a,b,Long.MAX_VALUE);
	}

	/**
	 * Merges two nodes while refusing incoming updates beyond a host-supplied
	 * timestamp bound. Validation is deliberately limited to the merge frontier;
	 * conforming DLFS writers maintain descendant timestamps no later than their
	 * ancestors.
	 */
	public static AVector<ACell> merge(AVector<ACell> a, AVector<ACell> b, long maximumTimestamp) {
		ChildMerge childMerge=(maximumTimestamp==Long.MAX_VALUE)
			?UNBOUNDED_CHILD_MERGE:new ChildMerge(maximumTimestamp);
		return merge(a,b,childMerge);
	}

	private static AVector<ACell> merge(AVector<ACell> a, AVector<ACell> b, ChildMerge childMerge) {
		long maximumTimestamp=childMerge.maximumTimestamp;
		if (a.equals(b)) return a;
		CVMLong timeA=getUTime(a);
		CVMLong timeB=getUTime(b);
		if (timeB.longValue()>maximumTimestamp) {
			throw new IllegalArgumentException("Future-dated foreign DLFS node");
		}

		// Deterministic merge time: max of input node timestamps
		CVMLong mergeTime = timeA.longValue() >= timeB.longValue() ? timeA : timeB;

		Index<AString, AVector<ACell>> contA = getDirectoryEntries(a);
		Index<AString, AVector<ACell>> contB = getDirectoryEntries(b);

		if ((contA!=null)&&(contB!=null)) {
			// Two directories. Merge live entries; reconcile against tombstones only if any exist.
			Index<AString, CVMLong> tombA = getTombstones(a);
			Index<AString, CVMLong> tombB = getTombstones(b);

			// Fast path: identical content except timestamp, take the most recent value
			if (Utils.equals(contA, contB) && Utils.equals(tombA, tombB)) {
				return timeA.compareTo(timeB)>=0?a:b;
			}

			// Live-entry merge needs no instrumentation: a purely-live change is never a conflict.
			Index<AString, AVector<ACell>> mergedDir = contA.mergeDifferences(contB, childMerge);

			// Common case: no deletions anywhere, so no live-vs-dead conflict is possible.
			if (tombA.isEmpty() && tombB.isEmpty()) {
				if ((mergedDir==contA)&&(timeA.longValue()>=timeB.longValue())) return a;
				AVector<ACell> metadataSource=timeA.longValue()>=timeB.longValue()?a:b;
				return buildDirectory(metadataSource, mergeTime, mergedDir, EMPTY_TOMBS);
			}

			// Merge tombstones, collecting only the names whose tombstone changed. Every live-vs-dead
			// conflict is necessarily among these (a name dead on both sides is not live in mergedDir),
			// so reconciliation is proportional to tombstone churn, not to live-entry churn.
			final HashSet<AString> tombTouched = new HashSet<>();
			Index<AString, CVMLong> mergedTomb = tombA.mergeDifferences(tombB, new MergeFunction<CVMLong>() {
				@Override
				public CVMLong merge(CVMLong ta, CVMLong tb) {
					if (tb!=null && tb.longValue()>maximumTimestamp) {
						throw new IllegalArgumentException("Future-dated foreign DLFS tombstone");
					}
					if (ta==null) return tb;
					if (tb==null) return ta;
					return ta.longValue()>=tb.longValue()?ta:tb;
				}
				@Override
				public CVMLong merge(Object key, CVMLong ta, CVMLong tb) {
					tombTouched.add((AString)key);
					return merge(ta,tb);
				}
			});

			// Reconcile: a name both live and tombstoned resolves by timestamp. On a
			// tie, preserve the first (own/current) operand, consistently with other
			// DLFS node conflicts. This is load-bearing for a local update merged with
			// an equal-timestamp stale snapshot.
			for (AString name : tombTouched) {
				AVector<ACell> live = mergedDir.get(name);
				if (live==null) continue;
				CVMLong death = mergedTomb.get(name);
				if (death==null) continue;
				long deathTime=death.longValue();
				long liveTime=getUTime(live).longValue();
				boolean ownDeleted=tombA.get(name)!=null;
				if ((deathTime>liveTime)||((deathTime==liveTime)&&ownDeleted)) {
					mergedDir = mergedDir.dissoc(name);   // newer deletion, or own deletion on tie
				} else {
					mergedTomb = mergedTomb.dissoc(name);  // newer live value, or own live value on tie
				}
			}

			// Return a unchanged if it already subsumes b (no allocation)
			if ((mergedDir==contA)&&(mergedTomb==tombA)&&(timeA.longValue()>=timeB.longValue())) return a;
			AVector<ACell> metadataSource=timeA.longValue()>=timeB.longValue()?a:b;
			return buildDirectory(metadataSource, mergeTime, mergedDir, mergedTomb);
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
	static AVector<ACell> buildDirectory(AVector<ACell> metadataSource, CVMLong utime,
			Index<AString, AVector<ACell>> entries, Index<AString, CVMLong> tombs) {
		return withDir(metadataSource, entries, tombs, utime);
	}

}
