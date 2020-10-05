package convex.core.data;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

import convex.core.Constants;
import convex.core.crypto.Hash;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.TODOException;
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
public abstract class ACell implements IWriteable, IValidated, IObject {

	/**
	 * We cache the Blob for the binary format of this Cell
	 */
	private Blob encoding;
	
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
	 * Validates the local structure of this cell. Called by validate() super implementation.
	 * 
	 * Should validate contained Refs, but should validate all other structure of this cell.
	 * 
	 * 
	 * @throws InvalidDataException 
	 */
	public abstract void validateCell() throws InvalidDataException;
	
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
	 * Attach the given Blob to this Cell, if no Blob is currently cached
	 * 
	 * Warning: Blob must be the correct canonical representation of this Cell,
	 * otherwise bad things may happen (incorrect hashcode, etc.)
	 * 
	 * @param data
	 */
	public void attachEncoding(Blob data) {
		if (this.encoding!=null) return;
		this.encoding=data;
	}
	
	/**
	 * Creates a Blob object representing this Cell. Should be called only after
	 * the cached blob has been checked.
	 * 
	 * @return
	 */
	protected Blob createEncoding() {
		int capacity=estimatedEncodingSize();
		ByteBuffer b=ByteBuffer.allocate(capacity);
		boolean done=false;
		while (!done) {
			try {
				b=write(b);
				done=true;
			} catch (BufferOverflowException be) {
				capacity=capacity*2+10;
				b=ByteBuffer.allocate(capacity);
			}
		}
		b.flip();
		return Blob.wrap(Utils.toByteArray(b));
	}

	/**
	 * Length of binary representation of this object
	 * @return The length of the encoded binary representation in bytes
	 */
	public final int encodedLength() {
		return Utils.checkedInt(getEncoding().length());
	}
	
	/**
	 * Hash of data encoding of this cell. Calling this method
	 * may force hash computation if needed.
	 * 
	 * @return The Hash of this cell's encoding.
	 */
	public final Hash getHash() {
		// final method to avoid any mistakes.
		return getEncoding().getContentHash();
	}
	
	/**
	 * Gets the Hash if already computed, or null if not yet available
	 * @return
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
	 * Default is the first bytes (big-endian) of the Cell encoding hash, since this is consistent with
	 * encoding-based equality. However, subclasses may provide more efficient hashcodes provided that
	 * 
	 * @return int hash code.
	 */
	@Override
	public int hashCode() {
		return getHash().hashCode();
	}
	
	@Override
	public boolean equals(Object a) {
		if (!(a instanceof ACell)) return false;
		return equals((ACell)a);
	}
	
	/**
	 * Checks for equality with another object. In general, data objects should be considered equal
	 * if they have the same canonical representation, i.e. the same hash value.
	 * 
	 * Subclasses may override this this they have a more efficient equals implementation.
	 * 
	 * @param a
	 * @return True if this cell is equal to the other object
	 */
	public boolean equals(ACell a) {
		if (this==a) return true; // important optimisation for e.g. hashmap equality
		if (a==null) return false;
		if (!(a.getClass()==this.getClass())) return false;
		return getHash().equals(a.getHash());
	}

	/**
	 * Writes this Cell's encoding to a ByteBuffer, including a tag byte which will be written first
	 *
	 * @param bb A ByteBuffer to which to write the encoding
	 * @return The passed ByteBuffer, after the representation of this object has been written.
	 */
	@Override
	public abstract ByteBuffer write(ByteBuffer bb);

	/**
	 * Writes this object to a ByteBuffer excluding the message tag
	 * 
	 * @param bb A ByteBuffer to write this object to
	 * @return The updated ByteBuffer
	 */
	public abstract ByteBuffer writeRaw(ByteBuffer bb);
	
	/**
	 * Estimate the encoded data size for this Cell. Used for quickly sizing buffers.
	 * Implementations should try to return a size that is likely to contain the entire object
	 * when represented in binary format, including the tag byte.
	 * 
	 * @return The estimated size for the binary representation of this object.
	 */
	public abstract int estimatedEncodingSize();
	
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
	 * Gets the cached blob representing this Cell in binary format, if it exists.
	 * 
	 * @return The cached blob for this cell, or null if not available. 
	 */
	public ABlob cachedBlob() {
		return encoding;
	}

	/**
	 * Calculates the total Memory Size for this Cell.
	 * 
	 * Requires any child Refs to be of persisted status at minimum, or you might get
	 * a MissingDataException
	 * 
	 * @return Memory Size of this Cell
	 */
	protected long calcMemorySize() {
		long  result=getEncoding().length();
		
		// add constant overhead
		result += Constants.MEMORY_OVERHEAD;
		
		// add size for each child Ref (might be zero if embedded)
		int n=getRefCount();
		for (int i=0; i<n; i++) {
			Ref<?> childRef=getRef(i);
			long childSize=childRef.getMemorySize();
			result += childSize;
		}
		return result;
	}
	
	/**
	 * Gets the Memory Size of this Cell, computing it if required.
	 * 
	 * @return Memory Size of this Cell
	 */
	public final long getMemorySize() {
		if (memorySize>=0) return memorySize;
		memorySize=(isEmbedded())?0:calcMemorySize();
		return memorySize;
	}

	/**
	 * Determines if this Cell Represents an embedded object. Embedded objects are encoded directly into
	 * the encoding of the containing Cell (avoiding the need for a hashed reference). 
	 * 
	 * @return true if Cell is embedded, false otherwise
	 */
	protected boolean isEmbedded() {
		return getEncoding().length()<=Format.MAX_EMBEDDED_LENGTH;
	}

	/**
	 * Gets the number of Refs contained within this Cell. This number is
	 * final / immutable for any given instance. Contained Refs may be either
	 * soft or embedded.
	 * 
	 * @return The number of Refs in this Cell
	 */
	public abstract int getRefCount();

	/**
	 * Gets the Ref for this Cell
	 */
	@SuppressWarnings("unchecked")
	public <R extends ACell> Ref<R> getRef() {
		if (cachedRef!=null) return (Ref<R>) cachedRef;
		Ref<ACell> newRef= RefDirect.create(this);
		cachedRef=newRef;
		return (Ref<R>) newRef;
	}
	
	/**
	 * Gets a numbered child Ref from within this Cell.
	 * 
	 * @param i Index of ref to get
	 * @return The Ref at the specified index
	 */
	public <R> Ref<R> getRef(int i) {
		if (getRefCount()==0) {
			throw new IndexOutOfBoundsException("No Refs to get in "+Utils.getClassName(this));
		} else {
			throw new TODOException(Utils.getClassName(this) +" does not yet implement getRef(i) for i = "+i);
		}
	}
	
	/**
	 * Updates all Refs in this object using the given function.
	 * 
	 * The function *must not* change the hash value of refs, in order to ensure
	 * structural integrity of modified data structures.
	 * 
	 * This is a building block for a very sneaky trick that enables use to do a lot
	 * of efficient operations on large trees of smart references.
	 * 
	 * Must return the same object if no Refs are altered.
	 */
	public ACell updateRefs(IRefFunction func) {
		if (getRefCount()==0) return this;
		throw new TODOException(Utils.getClassName(this) +" does not yet implement updateRefs(...)");
	}

	/**
	 * Gets an array of child refs for this object, in the order accessible by
	 * getRef. 
	 * 
	 * Concrete implementations may override this to optimise performance.
	 * 
	 * @param <R>
	 * @return Array of Refs
	 */
	@SuppressWarnings("unchecked")
	public <R> Ref<R>[] getChildRefs() {
		int n = getRefCount();
		Ref<R>[] refs = new Ref[n];
		for (int i = 0; i < n; i++) {
			refs[i] = getRef(i);
		}
		return refs;
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
		} else {
			if (this.memorySize==memorySize) return;
			throw new IllegalStateException("Attempting to attach memory size "+memorySize+" to object of class "+Utils.getClassName(this)+" which already has memorySize "+this.memorySize);
		}
	}

}
