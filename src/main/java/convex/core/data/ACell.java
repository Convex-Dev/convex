package convex.core.data;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

import convex.core.crypto.Hash;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Utils;

/**
 * Abstract base class for Cells.
 * 
 * Data cells may contain Refs to other Cells, in which case they must implement {@link IRefContainer}.
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
	private Blob binaryBlob;

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
		if (binaryBlob==null) binaryBlob=createBlob();
		return binaryBlob;
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
		if (this.binaryBlob!=null) return;
		this.binaryBlob=data;
	}
	
	/**
	 * Creates a Blob object representing this Cell. Should be called only after
	 * the cached blob has been checked.
	 * 
	 * @return
	 */
	protected Blob createBlob() {
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
		// SECURITY: use Keccak256 hash of serialised data representation
		// final method to avoid any mistakes.
		return getEncoding().getContentHash();
	}
	
	/**
	 * Gets the Hash if already computed, or null if not yet available
	 * @return
	 */
	protected final Hash checkHash() {
		if (binaryBlob==null) return null;
		return binaryBlob.storedHash;
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
		return binaryBlob;
	}


}
