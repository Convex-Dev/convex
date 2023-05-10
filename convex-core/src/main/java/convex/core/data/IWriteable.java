package convex.core.data;

public interface IWriteable {
	/**
	 * Writes this object to a byte array including an appropriate message tag
	 * 
	 * @param bs byte array to write this object to
	 * @param pos position at which to write the value
	 * @return The updated position
	 */
	public int encode(byte[] bs, int pos);
	
	/**
	 * Estimate the encoded data size for this Cell. Used for quickly sizing buffers.
	 * Implementations should try to return a size that is highly likely to contain the entire object
	 * when encoded, including the tag byte.
	 * 
	 * Should not traverse soft Refs, i.e. must be usable on arbitrary partial data structures
	 * 
	 * @return The estimated size for the binary representation of this object.
	 */
	public abstract int estimatedEncodingSize();
}
