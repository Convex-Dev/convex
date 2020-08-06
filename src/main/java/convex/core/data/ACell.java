package convex.core.data;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

import convex.core.crypto.Hash;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.TODOException;
import convex.core.util.Utils;

/**
 * Abstract base class for Cells.
 * 
 * Cells may contain Refs to other Cells, in which case they must implement {@link IRefContainer}.
 * 
 * All data objects intended for on-chain usage serialisation should extend this. The only 
 * exceptions are data objects which are embedded (inc. certain JVM types like Long etc.)
 * 
 * "It is better to have 100 functions operate on one data structure than 
 * to have 10 functions operate on 10 data structures." - Alan Perlis
 */
public abstract class ACell implements ICell, IWriteable {

	/**
	 * We cache the Blob for the binary format of this Cell
	 */
	private Blob encoding;

	@Override
	public void validate() throws InvalidDataException {
		validateCell();
	}
	
	/**
	 * Validates the local structure of this cell. Called by validate() super implementation.
	 * 
	 * Does not recursively validate contained Refs, but should validate all other structure of this cell
	 * @throws InvalidDataException 
	 */
	public abstract void validateCell() throws InvalidDataException;
	
	@Override
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

	@Override
	public final int encodedLength() {
		return Utils.checkedInt(getEncoding().length());
	}
	
	@Override
	public final Hash getHash() {
		// final method to avoid any mistakes.
		return getEncoding().getContentHash();
	}
	
	/**
	 * Gets the Hash if already computed, or null if not yet available
	 * @return
	 */
	protected final Hash checkHash() {
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
	 * Writes the encoded for of this Cell to a ByteBuffer.
	 * Will write the appropriate tag byte first
	 * @param bb A ByteBuffer to which to write the encoding
	 * @return The passed ByteBuffer, after the representation of this object has been written.
	 */
	@Override
	public abstract ByteBuffer write(ByteBuffer bb);

	/**
	 * Estimate the encoded data size for this Cell. Used for quickly sizing buffers.
	 * Implementations should try to return a size that is likely to contain the entire object
	 * when represented in binary format, including the tag byte.
	 * 
	 * @return The estimated size for the binary representation of this object.
	 */
	public abstract int estimatedEncodingSize();
	
	@Override
	public String toString() {
		StringBuilder sb=new StringBuilder();
		ednString(sb);
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
	 * Requires any child refs to be of persisted status at minimum
	 * 
	 * @return
	 */
	public long calcMemorySize() {
		long result=getEncoding().length();
		int n=getRefCount();
		for (int i=0; i<n; i++) {
			Ref<?> childRef=getRef(i);
			Long childSize=childRef.getMemorySize();
			if (childSize==null) {
				throw new Error("Null child size for: "+childRef + " with type "+Utils.getClassName(childRef.getValue()));
			}
			result += childSize;
		}
		return result;
	}

	/**
	 * Gets the number of Refs contained within this Cell. This number is
	 * final / immutable for any given instance.
	 * 
	 * @return The number of Refs in this Cell
	 */
	public abstract int getRefCount();

	/**
	 * Gets a numbered Ref from within this Cell.
	 * 
	 * @param i Index of ref to get
	 * @return The Ref at the specified index
	 */
	public <R> Ref<R> getRef(int i) {
		throw new IndexOutOfBoundsException("No Refs to get in "+Utils.getClassName(this));
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

}
