package convex.core.cvm;

import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.CAD3Encoder;
import convex.core.data.ExtensionValue;
import convex.core.data.Format;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.Core;

/**
 * Encoder for CVM values and data structures
 */
public class CVMEncoder extends CAD3Encoder {

	public static final CVMEncoder INSTANCE = new CVMEncoder();

	@Override
	public ACell read(Blob encoding,int offset) throws BadFormatException {
		return super.read(encoding,offset);
	}

	protected ACell readExtension(byte tag, Blob blob, int offset) throws BadFormatException {
		// We expect a VLQ Count following the tag
		long code=Format.readVLQCount(blob,offset+1);
		if (tag == CVMTag.CORE_DEF) return Core.fromCode(code);
		if (tag == CVMTag.ADDRESS) return Address.create(code);
		
		return ExtensionValue.create(tag, code);
	}
	
	protected ACell readDenseRecord(byte tag, Blob encoding, int offset) throws BadFormatException {
		switch (tag) {
		case CVMTag.STATE:
			return State.read(encoding, offset);
		}
		return super.read(tag, encoding,offset);
	}
}
