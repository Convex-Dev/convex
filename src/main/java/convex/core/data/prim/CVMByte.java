package convex.core.data.prim;

import convex.core.data.Tag;
import convex.core.exceptions.InvalidDataException;

public class CVMByte extends APrimitive {

	private final byte value;
	
	private static final CVMByte[] CACHE= new CVMByte[256];
	
	public CVMByte(byte value) {
		this.value=value;
	}

	public static CVMByte create(long value) {
		return CACHE[((int)(value))&0xFF];
	}
	
	static {
		for (int i=0; i<256; i++) {
			CACHE[i]=new CVMByte((byte)i);
		}
	}
	
	@Override
	public long longValue() {
		return 0xFFL&value;
	}
	
	@Override
	public int estimatedEncodingSize() {
		return 2;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		// Nothing to check. Always valid
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.BYTE;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		bs[pos++]=value;
		return pos;
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append(longValue());
	}

	@Override
	public void print(StringBuilder sb) {
		sb.append(longValue());
	}

	@Override
	public Class<?> numericType() {
		return Long.class;
	}

	@Override
	public double doubleValue() {
		return (double)value;
	}



}
