package convex.core.data;

import convex.core.exceptions.BadFormatException;
import convex.core.store.AStore;

/**
 * Abstract base class for encoders, which convert data to / from Blob instances
 */
public abstract class AEncoder<T> {

	public static final int FORMAT_V1=1;
	
	protected AStore store;
	
	/**
	 * Encodes a value as Blob
	 * @param a
	 * @return
	 */
	public abstract Blob encode(T a);
	
	public T decode(Blob encoding) throws BadFormatException {
		return read(encoding,0);
	}
	
	protected abstract T read(Blob encoding, int offset) throws BadFormatException;

	/**
	 * Reads a value from a Blob of data
	 * @param data Data to decode
	 * @return Value instance
	 * @throws BadFormatException If encoding format is invalid
	 */
	public abstract T decodeMultiCell(Blob enc) throws BadFormatException;
}
