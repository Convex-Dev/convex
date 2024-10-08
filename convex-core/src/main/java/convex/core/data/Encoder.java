package convex.core.data;

import convex.core.exceptions.BadFormatException;
import convex.core.store.AStore;

/**
 * Encoder for CAD3 data / stores
 */
public class Encoder {

	public static final int FORMAT_V1=1;
	
	protected AStore store;
	
	public Blob encode(ACell a) {
		return Format.encodedBlob(a);
	}
	
	public ACell decode(Blob encoding) throws BadFormatException {
		return Format.read(encoding);
	}
}
