package convex.core.data;

import java.util.function.Consumer;

import convex.core.Constants;
import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.TODOException;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.util.Utils;

/**
 * Abstract base class for Cells.
 * 
 * Cells may contain Refs to other Cells, which can be tested with getRefCount()
 * 
 * All data objects intended for on-chain usage / serialisation should extend this. 
 * 
 * "It is better to have 100 functions operate on one data structure than 
 * to have 10 functions operate on 10 data structures." - Alan Perlis
 */
public abstract class ACell extends AObject implements IWriteable, IValidated {
	/**
	 * We cache the computed memorySize. May be 0 for embedded objects
	 * -1 is initial value for when size is not calculated
	 */
	protected long memorySize=-1;
	
	/**
	 * Cached Ref. This is useful to manage persistence. Also cached Ref MUST refer to canonical value
	 */
	protected Ref<ACell> cachedRef=null;

	@Override
	public void validate() throws InvalidDataException {
		validateCell();
	}
	
	/**
	 * Validates the local structure and invariants of this cell. Called by validate() super implementation.
	 * 
	 * Should validate directly contained data, but should not validate all other structure of this cell. 
	 * 
	 * In particular, should not traverse potentially missing child Refs.
	 * 
	 * @throws InvalidDataException  If the Cell is invalid
	 */
	public abstract void validateCell() throws InvalidDataException;
	
	/**
	 * Hash of data Encoding of this cell, equivalent to the Value ID. Calling this method
	 * may force hash computation if needed.
	 * 
	 * @return The Hash of this cell's encoding.
	 */
	public final Hash getHash() {
		// final method to avoid any mistakes.
		return getEncoding().getContentHash();
	}
	
	/**
	 * Gets the tag byte for this cell. The tag byte is always equal to the 
	 * first byte of the Cell's canonical Encoding, and is sufficient to distinguish 
	 * how to read the rest of the encoding.
	 * 
	 * @return Tag byte for this Cell
	 */
	public abstract byte getTag();
	
	/**
	 * Gets the Hash if already computed, or null if not yet available
	 * @return Cached Hash value, or null if not available
	 */
	protected final Hash cachedHash() {
		Ref<ACell> ref=cachedRef;
		if (ref!=null) {
			Hash h=ref.cachedHash();
			if (h!=null) return h;
		}
		Blob enc=encoding;
		if (enc==null) return null;
		return enc.contentHash;
	}

	/**
	 * Gets the Java hashCode for this cell. Must be consistent with equals. 
	 * 
	 * Default is the hashCode of the Encoding blob, since this is consistent with
	 * encoding-based equality. However, different Types may provide more efficient hashcodes provided that
	 * the usual invariants are preserved
	 * 
	 * @return integer hash code.
	 */
	@Override
	public int hashCode() {
		return getEncoding().hashCode();
	}
	
	@Override
	public boolean equals(Object a) {
		if (a==this) return true; // Fast path, avoids cast
		if (!(a instanceof ACell)) return false; // Handles null
		return equals((ACell)a);
	}
	
	/**
	 * Gets the canonical encoded byte representation of this cell.
	 * 
	 * @return A Blob representing this cell in encoded form
	 */
	public final Blob getEncoding() {
		if (encoding!=null) return encoding;
		encoding=createEncoding();
		return encoding;
	}
	
	/**
	 * Gets the canonical representation of this Cell. 
	 * 
	 * O(1) if canonical representation is already generated, may be O(n) otherwise.
	 * 
	 * @return A Blob representing this cell in encoded form
	 */
	public final ACell getCanonical() {
		if (isCanonical()) return this;
		Ref<ACell> ref=getRef().ensureCanonical();
		if (cachedRef!=ref) cachedRef=ref;
		ACell c= ref.getValue();
		return c;
	}
	
	/**
	 * Checks for equality with another Cell. In general, Cells are considered equal
	 * if they have the same canonical representation, i.e. an identical encoding with the same hash value.
	 * 
	 * Subclasses SHOULD override this if they have a more efficient equals implementation. 
	 * 
	 * MUST NOT require reads from Store.
	 * 
	 * @param a Cell to compare with. May be null.
	 * @return True if this cell is equal to the other object
	 */
	public abstract boolean equals(ACell a);
	
	/**
	 * Generic Cell equality, use only if better implementation not available.
	 * @param a First cell to compare
	 * @param b Second cell to compare
	 * @return True if cells are equal, false otherwise
	 */
	protected static boolean genericEquals(ACell a, ACell b) {
		if (a==b) return true; // important optimisation for e.g. hashmap equality
		if ((b==null)||(a==null)) return false; // no non-null Cell is equal to null
		if (!(a.getTag()==b.getTag())) return false; // Different types never equal
		
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
	 * Writes this Cell's encoding to a byte array, including a tag byte which will be written first.
	 * 
	 * Cell must be canonical, or else an error may occur.
	 *
	 * @param bs A byte array to which to write the encoding
	 * @param pos The offset into the byte array
	 * 
	 * @return New position after writing
	 */
	public abstract int encode(byte[] bs, int pos);
	
	/**
	 * Writes this Cell's encoding to a byte array, excluding the tag byte.
	 *
	 * @param bs A byte array to which to write the encoding
	 * @param pos The offset into the byte array
	 * @return New position after writing
	 */
	protected abstract int encodeRaw(byte[] bs, int pos);
	
	/**
	 * Creates the encoding for this cell. Cell must be canonical, or else an error may occur.
	 * 
	 * The encoding itself is a raw Blob, which may be non-canonical. 
	 */
	@Override
	protected final Blob createEncoding() {
		int capacity=estimatedEncodingSize();
		byte[] bs;
		int pos=0;
		while (true) {
			try {
				bs=new byte[capacity];
				pos=encode(bs,pos);
				break;
			} catch (IndexOutOfBoundsException be) {
				if (capacity>Format.LIMIT_ENCODING_LENGTH) throw new Error("Encoding size limit exceeded in cell: "+this);
				
				// We really want to eliminate these, because exception handling is expensive
				// However don't want to be too conservative or we waste memory
				// System.out.println("Insufficient encoding size: "+capacity+ " for "+this.getClass());
				capacity=capacity*2+10;
			}
		}
		return Blob.wrap(bs,0,pos);
	}
	
	/**
	 * Returns the Java String representation of this Cell.
	 * 
	 * The String representation is intended to be a easy-to-read textual representation of the Cell's data content.
	 *
	 */
	@Override
	public String toString() {
		return print().toString();
	}
	
	/**
	 * Returns the CVM String representation of this Cell. Normally, this is as printed, but may be different for some types.
	 * 
	 * MUST return null in O(1) time if the length of the CVM String would exceed limit.
	 * 
	 * The String representation is intended to be a easy-to-read textual representation of the Cell's data content.
	 * @param limit Limit of CVM String length in UTF-8 bytes
	 * @return CVM String, or null if limit exceeded
	 *
	 */
	public AString toCVMString(long limit) {
		BlobBuilder bb=new BlobBuilder();
		if (!print(bb, limit)) return null;
		return bb.getCVMString();
	}

	/**
	 * Gets the cached blob representing this Cell's Encoding in binary format, if it exists.
	 * 
	 * @return The cached blob for this cell, or null if not available. 
	 */
	public Blob cachedEncoding() {
		return encoding;
	}

	/**
	 * Calculates the Memory Size for this Cell. Assumes not already calculated
	 * 
	 * Requires any child Refs to be either direct or of persisted in store at minimum, 
	 * or you might get a MissingDataException
	 * 
	 * @return Memory Size of this Cell
	 */
	protected long calcMemorySize() {	
		// add extra size for each child Ref (might be zero if embedded)
		long result=0;
		int n=getRefCount();
		for (int i=0; i<n; i++) {
			Ref<?> childRef=getRef(i);
			long childSize=childRef.getMemorySize();
			result=Utils.memoryAdd(result,childSize);
		}
		
		if (!isEmbedded()) {
			// We need to count this cell's own encoding length
			// Plus overhead for storage of non-embedded cell
			long encodingLength=getEncodingLength();
			result=Utils.memoryAdd(result,encodingLength+Constants.MEMORY_OVERHEAD);
		} 
		return result;
	}
	
	/**
	 * Method to calculate the encoding length of a Cell. May be overridden to avoid
	 * creating encodings during memory size calculations. This reduces hashing!
	 * 
	 * @return Exact encoding length of this Cell
	 */
	public int getEncodingLength() {
		return getEncoding().size();
	}

	/**
	 * Gets the Memory Size of this Cell, computing it if required.
	 * 
	 * The memory size is the total storage requirement for this cell. Embedded cells do not require storage for
	 * their own encoding, but may require storage for nested non-embedded Refs.
	 * 
	 * @return Memory Size of this Cell
	 */
	public final long getMemorySize() {
		long ms=memorySize;
		if (ms>=0) return ms;
		ms=calcMemorySize();
		this.memorySize=ms;
		return ms;
	}
	
	/**
	 * Gets the Memory Size of a Cell, computing it if required.
	 * 
	 * The memory size is the total storage requirement for this cell. Embedded cells do not require storage for
	 * their own encoding, but may require storage for nested non-embedded Refs.
	 * 
	 * @return Memory Size of this Cell
	 */
	public static long getMemorySize(ACell a) {
		return (a==null)?0:a.getMemorySize(); 
	}

	/**
	 * Determines if this Cell Represents an embedded object. Embedded objects are encoded directly into
	 * the encoding of the containing Cell (avoiding the need for a hashed reference). 
	 * 
	 * Subclasses should override this if they have a cheap O(1) 
	 * way to determine if they are embedded or otherwise. 
	 * 
	 * @return true if Cell is embedded, false otherwise
	 */
	public boolean isEmbedded() {
		if (memorySize==Format.FULL_EMBEDDED_MEMORY_SIZE) return true;
		if (cachedRef!=null) {
			int flags=cachedRef.flags;
			if ((flags&Ref.KNOWN_EMBEDDED_MASK)!=0) return true;
			if ((flags&Ref.NON_EMBEDDED_MASK)!=0) return false;
		} else {
			cachedRef=createRef();
		}
		boolean embedded= getEncodingLength()<=Format.MAX_EMBEDDED_LENGTH;
		cachedRef.flags|=(embedded)?Ref.KNOWN_EMBEDDED_MASK:Ref.NON_EMBEDDED_MASK;
		return embedded;
	}
	
	/**
	 * Returns true if this Cell is in a canonical representation for message writing.
	 * Non-canonical objects may be used on a temporary internal basis, they must always
	 * be converted to canonical representations for external use (e.g. Encoding).
	 * 
	 * @return true if the object is in canonical format, false otherwise
	 */
	public abstract boolean isCanonical();
	
	/**
	 * Converts this Cell to its canonical version. Must return this Cell if already canonical, may be O(n) in size of value otherwise.
	 * 
	 * @return Canonical version of Cell
	 */
	public abstract ACell toCanonical();
	
	/**
	 * Returns true if this cell instances represents a first class CVM Value allowable in the CVM state
	 * 
	 * Sub-structural cells that are not themselves first class values
	 * should return false
	 * 
	 * Records and types that are not permissible on the CVM should return false.
	 * 
	 * Pretty much everything else should return true.
	 * 
	 * Note: CVM values might not be in a canonical format, e.g. temporary data structures
	 * 
	 * @return true if the object is a CVM Value, false otherwise
	 */
	public abstract boolean isCVMValue();

	/**
	 * Gets the number of Refs contained within this Cell. This number is
	 * final / immutable for any given instance and is defined by the Cell encoding rules.
	 * WARNING: may not be valid id Cell is not canonical
	 * 
	 * Contained Refs may be either external or embedded.
	 * 
	 * @return The number of Refs in this Cell
	 */
	public abstract int getRefCount();

	/**
	 * Gets the Ref for this Cell, creating a new direct reference if necessary
	 * 
	 * @param <R> Type of Cell
	 * @return Ref for this Cell
	 */
	@SuppressWarnings("unchecked")
	public final <R extends ACell> Ref<R> getRef() {
		if (cachedRef!=null) return (Ref<R>) cachedRef;
		Ref<R> newRef=createRef();
		cachedRef=(Ref<ACell>) newRef;
		return newRef;
	}
	
	/**
	 * Creates a new Ref for this Cell
	 * @param <R> Type of Cell
	 * @return New Ref instance
	 */
	@SuppressWarnings("unchecked")
	protected <R extends ACell> Ref<R> createRef() {
		Ref<ACell> newRef= RefDirect.create(this);
		cachedRef=newRef;
		return (Ref<R>) newRef;
	}
	
	/**
	 * Gets a numbered child Ref from within this Cell.
	 * WARNING: May be unreliable is cell is not canonical
	 * 
	 * @param <R> Type of referenced Cell
	 * @param i Index of ref to get
	 * @return The Ref at the specified index
	 */
	public <R extends ACell> Ref<R> getRef(int i) {
		// This will always throw an error if not overridden. Provided for warning purposes if accidentally used.
		if (getRefCount()==0) {
			throw new IndexOutOfBoundsException("No Refs to get in "+Utils.getClassName(this));
		} 
		throw new TODOException(Utils.getClassName(this) +" does not yet implement getRef(i) for i = "+i);
	}
	
	/**
	 * Updates all Refs in this object using the given function.
	 * 
	 * The function *must not* change the hash value of Refs, in order to ensure
	 * structural integrity of modified data structures.
	 * 
	 * The implementation *should* re-attach any original encoding in order to
	 * prevent re-encoding or surplus hashing
	 * 
	 * This is a building block for a very sneaky trick that enables use to do a lot
	 * of efficient operations on large trees of smart references.
	 * 
	 * Must return the same object if no Refs are altered.
	 * @param func Ref update function
	 * @return Cell with updated Refs
	 */
	public ACell updateRefs(IRefFunction func) {
		if (getRefCount()==0) return this;
		throw new TODOException(Utils.getClassName(this) +" does not yet implement updateRefs(...)");
	}

	/**
	 * Gets an array of child Refs for this Cell, in the same order as order accessible by
	 * getRef. 
	 * 
	 * Concrete implementations may override this to optimise performance.
	 * 
	 * @param <R> Type of referenced Cell
	 * @return Array of Refs
	 */
	@SuppressWarnings("unchecked")
	public <R extends ACell> Ref<R>[] getChildRefs() {
		int n = getRefCount();
		Ref<R>[] refs = new Ref[n];
		for (int i = 0; i < n; i++) {
			refs[i] = getRef(i);
		}
		return refs;
	}
	
	/**
	 * Gets the most specific known runtime Type for this Cell.
	 * @return The Type of this Call
	 */
	public AType getType() {
		return Types.ANY;
	}

	/**
	 * Updates the memorySize of this Cell
	 * 
	 * Not valid for embedded Cells, may throw IllegalOperationException()
	 * 
	 * @param memorySize Memory size to assign
	 */
	public void attachMemorySize(long memorySize) {
		if (this.memorySize<0) {
			this.memorySize=memorySize;
			assert (this.memorySize>0) : "Attempting to attach memory size "+memorySize+" to object of class "+Utils.getClassName(this);
		} else {
			assert (this.memorySize==memorySize) : "Attempting to attach memory size "+memorySize+" to object of class "+Utils.getClassName(this)+" which already has memorySize "+this.memorySize;
		}
	}
	
	/**
	 * Updates the cached ref of this Cell
	 * 
	 * @param ref Ref to assign
	 */
	@SuppressWarnings("unchecked")
	public void attachRef(Ref<?> ref) {
		this.cachedRef=(Ref<ACell>) ref;
	}

	/**
	 * Creates an ANNOUNCED Ref with the given value in the current store.
	 * 
	 * Novelty handler is called for all new Refs that are persisted (recursively),
	 * starting from lowest levels.
	 * 
	 * @param <T> Type of Value
	 * @param value Value to announce
	 * @param noveltyHandler Novelty handler to call for any Novelty (may be null)
	 * @return Persisted Ref
	 */
	public static <T extends ACell> T createAnnounced(T value, Consumer<Ref<ACell>> noveltyHandler) {
		if (value==null) return null;
		return value.announce(noveltyHandler);
	}
	
	public <T extends ACell> T announce() {
		return announce(null);
	}
	
	public <T extends ACell> T announce(Consumer<Ref<ACell>> noveltyHandler) {
		Ref<T> ref = getRef();
		AStore store=Stores.current();
		ref= store.storeTopRef(ref, Ref.ANNOUNCED,noveltyHandler);
		return ref.getValue();
	}
	
	public <T extends ACell> T mark() {
		return mark(null);
	}
	
	@SuppressWarnings("unchecked")
	public <T extends ACell> T mark(Consumer<Ref<ACell>> noveltyHandler) {
		Ref<ACell> ref = getRef();
		AStore store=Stores.current();
		ref= store.storeTopRef(ref, Ref.MARKED,noveltyHandler);
		cachedRef=ref;
		return (T) this;
	}

	/**
	 * Creates a persisted Ref with the given value in the current store.
	 * 
	 * Novelty handler is called for all new Refs that are persisted (recursively),
	 * starting from lowest levels (depth first order)
	 * 
	 * @param <T> Type of Value
	 * @param value Any CVM value to persist
	 * @param noveltyHandler Novelty handler to call for any Novelty (may be null)
	 * @return Persisted Ref
	 */
	public static <T extends ACell> Ref<T> createPersisted(T value, Consumer<Ref<ACell>> noveltyHandler) {
		Ref<T> ref = Ref.get(value);
		if (ref.isPersisted()) return ref;
		AStore store=Stores.current();
		ref = (Ref<T>) store.storeTopRef(ref, Ref.PERSISTED,noveltyHandler);
		return ref;
	}

	/**
	 * Creates a persisted Ref with the given value in the current store. Returns
	 * the current Ref if already persisted
	 * 
	 * @param <T> Type of Value
	 * @param value Any CVM value to persist
	 * @return Ref to the given value
	 */
	public static <T extends ACell> Ref<T> createPersisted(T value) {
		return createPersisted(value, null);
	}

	/**
	 * Tests if this Cell is completely encoded, i.e. has no external Refs. This implies that the 
	 * complete Cell can be represented in a single encoding.
	 * @return true if completely encoded, false otherwise
	 */
	public boolean isCompletelyEncoded() {
		if (memorySize==Format.FULL_EMBEDDED_MEMORY_SIZE) return true; // fast path for fully embedded
		if (!isCanonical()) {
			throw new Error("Checking whether a non-canonical cell is encoded. Not a good idea, any ref assumptions may be invalid: "+this.getType());
		}
		int n=getRefCount();
		for (int i=0; i<n; i++) {
			Ref<ACell> r=getRef(i);
			if (!r.isEmbedded()) return false;
			ACell child=r.getValue();
			if (child!=null) {
				child=child.getCanonical();
				if (!child.isCompletelyEncoded()) return false; // Should be safe from missing?
			}
		}
		return true;
	}

}
