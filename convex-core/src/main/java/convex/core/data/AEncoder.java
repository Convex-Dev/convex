package convex.core.data;

import convex.core.exceptions.BadFormatException;
import convex.core.store.AStore;

/**
 * Encoder for CAD3 data / stores
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
	
	public abstract T decode(Blob encoding) throws BadFormatException;
}
