package convex.core.data;

import java.nio.ByteBuffer;

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
	 * Writes this object to a ByteBuffer including an appropriate message tag
	 * 
	 * @param bb ByteBuffer to write to
	 * @return The updated ByteBuffer
	 */
	public ByteBuffer write(ByteBuffer bb);
	
	/**
	 * Estimate the encoded data size for this Cell. Used for quickly sizing buffers.
	 * Implementations should try to return a size that is likely to contain the entire object
	 * when represented in binary format, including the tag byte.
	 * 
	 * @return The estimated size for the binary representation of this object.
	 */
	public abstract int estimatedEncodingSize();
}
