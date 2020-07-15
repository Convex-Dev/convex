package etch.api;

import java.nio.ByteBuffer;

/**
 * Interface for object storage adapters.
 * 
 */
public interface IStorage<T> {

	/**
	 * Writes this Object to the specified ByteBuffer as a new stored instance, at the 
	 * current position of the ByteBuffer
	 * 
	 * @param bb A writable ByteBuffer, which must have sufficient capacity for the object to be written
	 * @return the object after writing
	 */
	public T writeNew(T object, ByteBuffer bb);
	
	/**
	 * Writes this Object to the specified ByteBuffer, merging with a previously stored instance in-place.
	 * 
	 * Should be an idempotent merge operation: repeated writes should return the same value. This property is
	 * necessary for monotonically increasing fields etc.
	 * 
	 * @param bb A writable ByteBuffer, which must have sufficient capacity for the object to be written
	 * @return the object after writing, or an updated Object if the merge causes any changes.
	 */
	public T writeUpdate(T object, ByteBuffer bb);
	
	/**
	 * Reads the Object from the specified ByteBuffer, starting at the current position.
	 * 
	 * @param bb A ByteBuffer
	 * @return this Object, or an updated Object if the merge causes any changes.
	 */
	public T read(ByteBuffer bb);
}
