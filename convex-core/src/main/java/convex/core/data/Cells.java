package convex.core.data;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.Collection;
import java.util.function.Consumer;

import convex.core.Result;
import convex.core.data.impl.DummyCell;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.ParseException;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.util.Utils;

/**
 * Static utility class for dealing with cells
 */
public class Cells {

	/**
	 * An empty Java array of cells
	 */
	public static final ACell[] EMPTY_ARRAY = new ACell[0];
	
	/**
	 * The maximum number of branches possible from a single Cell encoding
	 * TODO: Verify this is 68 (vector with 16 embedded children with 4 branches each + embedded tail with 4 branches)
	 */
	public static final int MAX_BRANCH_COUNT = 68;

	public static final ACell DUMMY = new DummyCell();

	/**
	 * Equality method allowing for nulls
	 *
	 * @param a First value
	 * @param b Second value
	 * @return true if arguments are equal, false otherwise
	 */
	public static boolean equals(ACell a, ACell b) {
		if (a == b) return true;
		if (a == null) return false; // b can't be null because of above line
		return a.equals(b); // fall back to ACell equality
	}
	
	/**
	 * Generic Cell equality, used if better implementation not available.
	 * @param a First cell to compare
	 * @param b Second cell to compare
	 * @return True if cells are equal, false otherwise
	 */
	public static boolean equalsGeneric(ACell a, ACell b) {
		if (a==b) return true; // important optimisation for e.g. hashmap equality
		if ((b==null)||(a==null)) return false; // no non-null Cell is equal to null
		if (!(a.getTag()==b.getTag())) return false; // Different tags never equal
		
		// Check hashes for equality if they exist
		Hash ha=a.cachedHash();
		if (ha!=null) {
			Hash hb=b.cachedHash();
			if (hb!=null) return ha.equals(hb);
		}
	
		// Else default to checking encodings
		// We would need to get encodings anyway to compute a Hash....
		return a.getEncoding().equals(b.getEncoding());
	}

	/**
	 * Converts any collection object to an ACell[] array. Elements must be Cells.
	 *
	 * @param anyArray Array to convert
	 * @return ACell[] array
	 */
	@SuppressWarnings("unchecked")
	public static ACell[] toCellArray(Object any) {
		if (any instanceof Collection) {
			return toCellArray((Collection<ACell>)any);
		} else if (any.getClass().isArray()) {
			int n = Array.getLength(any);
			ACell[] result = new ACell[n];
			for (int i = 0; i < n; i++) {
				result[i] = (ACell) Array.get(any, i);
			}
			return result;
		} else {
			throw new IllegalArgumentException("Can't get cell array from "+Utils.getClassName(any));
		}
	}
	
	/**
	 * Converts any array to an ACell[] array. Elements must be Cells.
	 *
	 * @param coll Array to convert
	 * @return ACell[] array
	 */
	public static ACell[] toCellArray(Collection<? extends ACell> coll) {
		int n=coll.size();
		if (n==0) return Cells.EMPTY_ARRAY;
		return coll.toArray(new ACell[n]);
	}
	

	/**
	 * Gets the number of Refs directly contained in a Cell (will be zero if the
	 * Cell is not a Ref container)
	 *
	 * @param a Cell to check (may be null)
	 * @return Number of Refs in the object.
	 */
	public static int refCount(ACell a) {
		if (a==null) return 0;
		return a.getRefCount();
	}
	
	/**
	 * Gets the number of Branches directly contained in a Cell (will be zero if the
	 * Cell is not a Ref container)
	 *
	 * @param a Cell to check (may be null)
	 * @return Number of Refs in the object.
	 */
	public static int branchCount(ACell v) {
		if (v==null) return 0;
		return v.getBranchCount();
	}

	/**
	 * Gets a Ref from a Cell by index
	 * @param <R> Type of cell
	 * @param cell Cell to read Ref from
	 * @param index Numerical index of Ref
	 * @throws IndexOutOfBoundsException if the index is out of range for the Cell
	 * @return Ref for cell
	 */
	public static <R extends ACell> Ref<R> getRef(ACell cell, int index) {
		if (cell==null) throw new IndexOutOfBoundsException("getRef called on null");
		return cell.getRef(index);
	}

	/**
	 * Checks if a Cell is a valid CVM value
	 * @param a Cell to check
	 * @return True if CVM VAlue, false otherwise
	 */
	public static boolean isCVM(ACell a) {
		if (a==null) return true;
		return a.isCVMValue();
	}
	
	/**
	 * Checks if a Cell is completely encoded, i.e. has no external Refs
	 * @param a Cell to check
	 * @return True if completely encoded, false otherwise
	 */
	public static boolean isCompletelyEncoded(ACell a) {
		if (a==null) return true;
		return a.isCompletelyEncoded();
	}
	
	/**
	 * Persist a cell in the current store
	 * @param a Cell to persist
	 * @return Cell after persisting (may be the same Cell if no change in cell hierarchy)
	 * @throws IOException in case of IO error during persistence
	 */
	public static <T extends ACell> T persist(T a) throws IOException {
		AStore store=Stores.current();
		return persist(a,store);
	}

	/**
	 * Persist a cell in the given store
	 * @param a Cell to persist
	 * @param store Store instance to persist in
	 * @return Cell after persisting (may be the same Cell if no change in cell hierarchy)
	 * @throws IOException in case of IO error during persistence
	 */
	public static <T extends ACell> T persist(T a, AStore store) throws IOException {
		Ref<T> ref=Ref.get(a);
		Ref<T> sref=store.storeTopRef(ref, Ref.PERSISTED, null);
		return sref.getValue();
	}
	
	/**
	 * Persist a cell in the given store
	 * @param a Cell to persist
	 * @param store Store instance to persist in
	 * @return Cell after persisting (may be the same Cell if no change in cell hierarchy)
	 * @throws IOException in case of IO error during persistence
	 */
	public static <T extends ACell> T store(T a, AStore store) throws IOException {
		Ref<T> ref=Ref.get(a);
		Ref<T> sref=store.storeTopRef(ref, Ref.STORED, null);
		return sref.getValue();
	}

	/**
	 * Announces a Cell, reporting as novelty any cells that have not been previously announced
	 * @param a Cell to announce
	 * @param noveltyHandler Handler for novelty values
	 * @return Cell after announcing (may be the same Cell if no change in cell hierarchy)
	 * @throws IOException in case of IO error during persistence
	 */
	public static <T extends ACell> T announce(T a, Consumer<Ref<ACell>> noveltyHandler) throws IOException {
		if (a==null) {
			return null; // null is never "novelty"
		};
		Ref<T> ref=Stores.current().storeTopRef(a.getRef(), Ref.ANNOUNCED, noveltyHandler);
		return ref.getValue();
	}

	/**
	 * Gets the Hash (Value ID of a cell)
	 * @param a Cell to get hash from
	 * @return Hash value
	 */
	public static Hash getHash(ACell a) {
		if (a==null) return Hash.NULL_HASH;
		// this picks up a hash in the Ref if available, otherwise populates it for future use
		return a.getRef().getHash(); 
	}

	public static Blob getEncoding(ACell a) {
		if (a==null) return Blob.NULL_ENCODING;
		return a.getEncoding();
	}

	/**
	 * Get the full storage size of a value, including size of an embedded encoding
	 * @param a Cell to get size for (may be null)
	 * @return Storage size
	 */
	public static long storageSize(ACell a) {
		if (a==null) return 1;
		long memSize=a.getMemorySize();
		if (a.isEmbedded()) {
			memSize+=a.getEncodingLength();
		} 
		return memSize;
	}

	/**
	 * Visit all non-embedded branches from a Cell. Recursively checks embedded children.
	 * @param a Cell within which to visit children.
	 * @param visitor Visitor instance
	 */
	public static void visitBranchRefs(ACell a, Consumer<Ref<?>> visitor) {
		if (a==null) return;
		int n=a.getRefCount();
		for (int i=0; i<n; i++) {
			Ref<?> ref=a.getRef(i);
			if (ref.isEmbedded()) {
				ACell child=ref.getValue();
				if (child!=null) {
					visitBranchRefs(ref.getValue(),visitor);
				}
			} else {
				visitor.accept(ref);
			}
		}
		return;
	}
	
	/**
	 * Visit all non-embedded branches from a Cell. Recursively checks embedded children. May cause store reads.
	 * @param a Cell within which to visit children.
	 * @param visitor Visitor instance
	 */
	public static void visitBranches(ACell a, Consumer<ACell> visitor) {
		if (a==null) return;
		int n=a.getRefCount();
		for (int i=0; i<n; i++) {
			Ref<?> ref=a.getRef(i);
			if (ref.isEmbedded()) {
				ACell child=ref.getValue();
				if (child!=null) {
					visitBranches(ref.getValue(),visitor);
				}
			} else {
				visitor.accept(ref.getValue());
			}
		}
		return;
	}

	/**
	 * Intern a Cell permanently in memory (for JVM lifetime). 
	 * 
	 * SECURITY: do not do this for any generated structure from external sources. Attackers could DoS your memory.
	 * 
	 * @param <T> Type of Cell
	 * @param value Value to intern
	 * @return Interned Cell
	 */
	public static <T extends ACell> T intern(T value) {
		Ref<T> ref=Ref.get(value);
		if (ref.isInternal()) return value;
		
		ref.setFlags(Ref.mergeFlags(ref.getFlags(), Ref.INTERNAL));
		return value;
	}

	@SuppressWarnings("unchecked")
	public static ACell createTagged(Symbol sym, ACell value) throws ParseException {
		switch (sym.getName().toString()) {
			case "Index": {
				if (!(value instanceof AHashMap)) throw new ParseException(sym+" tag must be on a map");
				Index<ABlobLike<?>,ACell> index= Index.create((AHashMap<ABlobLike<?>,ACell>)value);
				if (index==null) throw new ParseException("Invalid #Index keys");
				return index;
			}
			case "Result": {
				if (!(value instanceof AHashMap)) throw new ParseException(sym+" tag must be on a map");
				Result r= Result.fromData((AHashMap<ABlobLike<?>,ACell>)value);
				if (r==null) throw new ParseException("Invalid #Result keys");
				return r;
			}
			case "Signed": {
				if (!(value instanceof AHashMap)) throw new ParseException(sym+" tag must be on a map");
				SignedData<?> r= SignedData.fromData((AHashMap<Keyword,ACell>)value);
				if (r==null) throw new ParseException("Invalid #Signed keys");
				return r;
			}
		}
		return value;
	}

	public static void validate(ACell cell) throws InvalidDataException {
		if (cell==null) return; // OK, this is the null value
		Ref<?> ref=cell.getRef();
		if (ref.isValidated()) return;
		cell.validateCell();
	}

	/**
	 * Gets the caches hash for a cell if available
	 * @param k Any cell (can be null)
	 * @return Hash of cell (value ID) or null if not cached
	 */
	public static Hash cachedHash(ACell k) {
		if (k==null) return Hash.NULL_HASH;
		return k.cachedHash();
	}

	/**
	 * Returns true if the object is a canonical data object. Canonical data objects
	 * can be used as first class decentralised data objects.
	 * 
	 * @param o Value to test
	 * @return true if object is canonical, false otherwise.
	 */
	public static boolean isCanonical(ACell o) {
		if (o==null) return true;
		return o.isCanonical();
	}

	/**
	 * Determines if an object should be embedded directly in the encoding rather
	 * than referenced with a Ref / hash. Defined to be true for most small objects.
	 * 
	 * @param cell Value to test
	 * @return true if object is embedded, false otherwise
	 */
	public static boolean isEmbedded(ACell cell) {
		if (cell == null) return true;
		return cell.isEmbedded();
	}

	/**
	 * Gets the encoded Blob for an object in CAD3 format
	 * 
	 * @param o The object to encode
	 * @return Encoded data as a blob
	 */
	public static Blob encode(ACell o) {
		if (o==null) return Blob.NULL_ENCODING;
		return o.getEncoding();
	}

	public static int getEncodingLength(ACell value) {
		if (value==null) return 1;
		return value.getEncodingLength();
	}



}
