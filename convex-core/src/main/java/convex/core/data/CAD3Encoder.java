package convex.core.data;

import convex.core.exceptions.BadFormatException;

public class CAD3Encoder extends AEncoder<ACell> {

	public Blob encode(ACell a) {
		return Format.encodedBlob(a);
	}
	
	public ACell decode(Blob encoding) throws BadFormatException {
		return Format.read(encoding);
	}
}
