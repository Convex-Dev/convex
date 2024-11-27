package convex.core.data;

import convex.core.exceptions.BadFormatException;

public class CVMEncoder extends CAD3Encoder {

	public static final CVMEncoder INSTANCE = new CVMEncoder();

	@Override
	public ACell decodeMultiCell(Blob enc) throws BadFormatException  {
		return Format.decodeMultiCell(enc);
	}

}
