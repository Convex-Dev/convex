package convex.core.data;

import convex.core.exceptions.BadFormatException;

/**
 * Base Encoder for CAD3 data / stores
 */
public abstract class CAD3Encoder extends AEncoder<ACell> {

	public Blob encode(ACell a) {
		return Format.encodedBlob(a);
	}
	
	public ACell decode(Blob encoding) throws BadFormatException {
		return Format.read(encoding);
	}
	
	/**
	 * Reads a cell value from a Blob of data, allowing for non-embedded branches following the first cell
	 * @param data Data to decode
	 * @return Cell instance
	 * @throws BadFormatException If CAD3 encoding format is invalid
	 */
	@Override
	public abstract ACell decodeMultiCell(Blob enc) throws BadFormatException;
}
