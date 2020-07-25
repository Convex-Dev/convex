package convex.core.data;

import java.nio.ByteBuffer;

import convex.core.crypto.Hash;

/**
 * Interface for all custom data object cells that can be serialised on chain
 */
public interface ICell extends IValidated, IWriteable, IObject {
	/**
	 * Hash of data encoding of this cell. Calling this method
	 * may force hash computation if needed.
	 * 
	 * @return The Hash of this cell's encoding.
	 */
	public Hash getHash();
	
	/**
	 * Gets the encoded byte representation of this cell.
	 * 
	 * @return A blob representing this cell in encoded form
	 */
	public AArrayBlob getEncoding();

	/**
	 * Length of binary representation of this object
	 * @return The length of the encoded binary representation in bytes
	 */
	public int encodedLength();

	/**
	 * Writes this cell's encoding to a ByteBuffer, including a tag
	 * @param bb
	 * @return The updated ByteBuffer
	 */
	@Override
	public ByteBuffer write(ByteBuffer bb);

}