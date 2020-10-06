package convex.core.data;

import java.nio.ByteBuffer;

import convex.core.exceptions.BadFormatException;

public class Strings {

	
	/**
	 * Reads a CVM String value from a bytebuffer. Assumes tag already read.
	 * 
	 * @param bb
	 * @return
	 * @throws BadFormatException 
	 */
	public static AString read(ByteBuffer bb) throws BadFormatException {
		long length=Format.readVLCLong(bb);
		if (length<0) throw new BadFormatException("Negative string length!");
		if (length>Integer.MAX_VALUE) throw new BadFormatException("String length too long! "+length);
		if (length<=StringShort.MAX_LENGTH) {
			return StringShort.read((int)length,bb);
		}
		return null;
	}
}
