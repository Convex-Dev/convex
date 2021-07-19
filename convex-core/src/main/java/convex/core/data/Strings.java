package convex.core.data;

import java.nio.ByteBuffer;

import convex.core.exceptions.BadFormatException;

public class Strings {

	public static final StringShort EMPTY = StringShort.create("");
	public static final StringShort NIL = StringShort.create("nil");

	/**
	 * Reads a CVM String value from a bytebuffer. Assumes tag already read.
	 * 
	 * @param bb
	 * @return
	 * @throws BadFormatException 
	 */
	public static AString read(ByteBuffer bb) throws BadFormatException {
		long length=Format.readVLCLong(bb);
		if (length==0) return EMPTY;
		if (length<0) throw new BadFormatException("Negative string length!");
		if (length>Integer.MAX_VALUE) throw new BadFormatException("String length too long! "+length);
		if (length<=StringShort.MAX_LENGTH) {
			return StringShort.read((int)length,bb);
		}
		return StringTree.read((int)length,bb);
	}

	public static AString create(String s) {
		int len=s.length();
		if (len==0) return EMPTY;
		if (len<=StringShort.MAX_LENGTH) {
			return StringShort.create(s);
		}
		return StringTree.create(s);
	}

	public static AString empty() {
		return EMPTY;
	}
}
