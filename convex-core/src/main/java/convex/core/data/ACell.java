package convex.core.data;

import java.nio.ByteBuffer;
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
 * All data objects intended for on-chain usage serialisation should extend this. The only 
 * exceptions are data objects which are Embedded (inc. certain JVM types like Long etc.)
 * 
 * "It is better to have 100 functions operate on one data structure than 
 * to have 10 functions operate on 10 data structures." - Alan Perlis
 */
public abstract class ACell extends AObject implements IWriteable, IValidated {

	
	/**
	 * An empty Java array of cells
	 */
	public static final ACell[] EMPTY_ARRAY = new ACell[0];

	/**
	 * We cache the computed memorySize. May be 0 for embedded objects
	 * -1 is initial value for when size is not calculated
	 */
	private long memorySize=-1;
	
	/**
	 * Cached Ref. This is useful to manage persistence
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
	 * Gets the tag byte for this cell. The tag byte will be the first byte of the encoding
	 * @return Tag byte for this Cell
	 */
	public abstract byte getTag();
	
	/**
	 * Gets the Hash if already computed, or null if not yet available
	 * @return Cached Hash value, or null if not available
	 */
	protected final Hash cachedHash() {
		if (cachedRef!=null) {
			Hash h=cachedRef.cachedHash();
			if (h!=null) return h;
		}
		if (encoding==null) return null;
		return encoding.contentHash;
	}

	/**
	 * Gets the Java hashCode for this cell. Must be consistent with equals. 
	 * 
	 * Default is the first bytes (big-endian) of the Cell Encoding's hash, since this is consistent with
	 * encoding-based equality. However, different Types may provide more efficient hashcodes provided that
	 * the usual invariants are preserved
	 * 
	 * @return integer hash code.
	 */
	@Override
	public int hashCode() {
		return getHash().firstInt();
	}
	
	@Override
	public final boolean equals(Object a) {
		if (!(a instanceof ACell)) return false;
		return equals((ACell)a);
	}
	
	/**
	 * Gets the encoded byte representation of this cell.
	 * 
	 * @return A blob representing this cell in encoded form
	 */
	public final Blob getEncoding() {
		if (encoding==null) encoding=createEncoding();
		return encoding;
	}
	
	/**
	 * Checks for equality with another object. In general, data objects should be considered equal
	 * if they have the same canonical representation, i.e. an identical encoding with the same hash value.
	 * 
	 * Subclasses should override this if they have a more efficient equals implementation.
	 * 
	 * @param a Cell to compare with. May be null??
	 * @return True if this cell is equal to the other object
	 */
	public boolean equals(ACell a) {
		if (this==a) return true; // important optimisation for e.g. hashmap equality
		if (a==null) return false;
		if (!(a.getTag()==this.getTag())) return false;
		return getEncoding().equals(a.getEncoding());
	}

	/**
	 * Writes this Cell's encoding to a ByteBuffer, including a tag byte which will be written first
	 *
	 * @param bb A ByteBuffer to which to write the encoding
	 * @return The passed ByteBuffer, after the representation of this object has been written.
	 */
	@Override
	public final ByteBuffer write(ByteBuffer bb) {
		return getEncoding().writeToBuffer(bb);
	}
	
	/**
	 * Writes this Cell's encoding to a byte array, including a tag byte which will be written first
	 *
	 * @param bs A byte array to which to write the encoding
	 * @param pos The offset into the byte array
	 * 
	 * @return New position after writing
	 */
	public abstract int encode(byte[] bs, int pos);
	
	/**
	 * Writes this Cell's encoding to a byte array, excluding the tag byte
	 *
	 * @param bs A byte array to which to write the encoding
	 * @param pos The offset into the byte array
	 * @return New position after writing
	 */
	public abstract int encodeRaw(byte[] bs, int pos);
	
	@Override
	public final Blob createEncoding() {
		int capacity=estimatedEncodingSize();
		byte[] bs=new byte[capacity];
		int pos=0;
		boolean done=false;
		while (!done) {
			try {
				pos=encode(bs,pos);
				done=true;
			} catch (IndexOutOfBoundsException be) {
				// We really want to eliminate these, because exception handling is expensive
				// System.out.println("Insufficient encoding size: "+capacity+ " for "+this.getClass());
				capacity=capacity*2+10;
				bs=new byte[capacity];
			}
		}
		return Blob.wrap(bs,0,pos);
	}
	
	/**
	 * Returns the String representation of this Cell.
	 * 
	 * The String representation is intended to be a easy-to-read textual representation of the Cell's data content.
	 *
	 */
	@Override
	public String toString() {
		StringBuilder sb=new StringBuilder();
		print(sb);
		return sb.toString();
	}

	/**
	 * Gets the cached blob representing this Cell's Encoding in binary format, if it exists.
	 * 
	 * @return The cached blob for this cell, or null if not available. 
	 */
	public ABlob cachedEncoding() {
		return encoding;
	}

	/**
	 * Calculates the Memory Size for this Cell.
	 * 
	 * Requires any child Refs to be either Direct or of persisted status at minimum, 
	 * or you might get a MissingDataException
	 * 
	 * @return Memory Size of this Cell
	 */
	protected long calcMemorySize() {	
		// add size for each child Ref (might be zero if embedded)
		long result=0;
		int n=getRefCount();
		for (int i=0; i<n; i++) {
			Ref<?> childRef=getRef(i);
			long childSize=childRef.getMemorySize();
			result+=childSize;
		}
		
		if (!isEmbedded()) {
			// We need to count this cell's own encoding length
			result+=getEncodingLength();
			
			// Add overhead for storage of non-embedded cell
			result+=Constants.MEMORY_OVERHEAD;
		} 
		return result;
	}
	
	/**
	 * Method to calculate the encoding length of a Cell. May be overridden to avoid
	 * creating encodings during memory size calculations. This reduces hashing!
	 * 
	 * @return Exact encoding length of this Cell
	 */
	public long getEncodingLength() {
		return getEncoding().count();
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
	 * Determines if this Cell Represents an embedded object. Embedded objects are encoded directly into
	 * the encoding of the containing Cell (avoiding the need for a hashed reference). 
	 * 
	 * Subclasses should override this if they have a cheap O(1) 
	 * way to determine if they are embedded or otherwise. 
	 * 
	 * @return true if Cell is embedded, false otherwise
	 */
	public boolean isEmbedded() {
		if (cachedRef!=null) {
			int flags=cachedRef.flags;
			if ((flags&Ref.KNOWN_EMBEDDED_MASK)!=0) return true;
			if ((flags&Ref.NON_EMBEDDED_MASK)!=0) return false;
		}
		boolean embedded= getEncodingLength()<=Format.MAX_EMBEDDED_LENGTH;
		if (cachedRef!=null) {
			cachedRef.flags|=(embedded)?Ref.KNOWN_EMBEDDED_MASK:Ref.NON_EMBEDDED_MASK;
		}
		return embedded;
	}
	
	/**
	 * Returns true if this Cell is in a canonical format for message writing.
	 * Reading or writing a non-canonical value should be considered illegal
	 * 
	 * @return true if the object is in canonical format, false otherwise
	 */
	public abstract boolean isCanonical();
	
	/**
	 * Converts this Cell to its canonical version. Returns this if already canonical
	 * 
	 * @return Canonical version of Cell
	 */
	public abstract ACell toCanonical();
	
	/**
	 * Returns true if this object represents a first class CVM Value. Sub-structural cells that are not themselves first class values
	 * should return false.
	 * 
	 * CVM values might not be in a canonical format, e.g. temporary data structures
	 * 
	 * @return true if the object is a CVM Value, false otherwise
	 */
	public abstract boolean isCVMValue();

	/**
	 * Gets the number of Refs contained within this Cell. This number is
	 * final / immutable for any given instance. 
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
		return createRef();
	}
	
	/**
	 * Creates a new Ref for this Cell
	 * @param <R> Type of Cell
	 * @return New Ref instance
	 */
	@SuppressWarnings("unchecked")
	protected <R extends ACell> Ref<R> createRef() {
		Ref<ACell> newRef= RefDirect.create(this,cachedHash());
		cachedRef=newRef;
		return (Ref<R>) newRef;
	}
	
	/**
	 * Gets a numbered child Ref from within this Cell.
	 * 
	 * @param <R> Type of referenced Cell
	 * @param i Index of ref to get
	 * @return The Ref at the specified index
	 */
	public <R extends ACell> Ref<R> getRef(int i) {
		// This will always throw an error if not overridden. Provided for warning purposes if accidentally used.
		if (getRefCount()==0) {
			throw new IndexOutOfBoundsException("No Refs to get in "+Utils.getClassName(this));
		} else {
			throw new TODOException(Utils.getClassName(this) +" does not yet implement getRef(i) for i = "+i);
		}
	}
	
	/**
	 * Updates all Refs in this object using the given function.
	 * 
	 * The function *must not* change the hash value of Refs, in order to ensure
	 * structural integrity of modified data structures.
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
	public static <T extends ACell> Ref<T> createAnnounced(T value, Consumer<Ref<ACell>> noveltyHandler) {
		Ref<T> ref = Ref.get(value);
		AStore store=Stores.current();
		return (Ref<T>) store.storeTopRef(ref, Ref.ANNOUNCED,noveltyHandler);
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
	@SuppressWarnings("unchecked")
	public static <T extends ACell> Ref<T> createPersisted(T value, Consumer<Ref<ACell>> noveltyHandler) {
		Ref<T> ref = Ref.get(value);
		if (ref.isPersisted()) return ref;
		AStore store=Stores.current();
		ref = (Ref<T>) store.storeTopRef(ref, Ref.PERSISTED,noveltyHandler);
		value.cachedRef=(Ref<ACell>)ref;
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

}
