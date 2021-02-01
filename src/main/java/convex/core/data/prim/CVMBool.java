package convex.core.data.prim;

import convex.core.data.Tag;
import convex.core.exceptions.InvalidDataException;

public class CVMBool extends APrimitive {

	private final boolean value;
	
	public static final CVMBool TRUE=new CVMBool(true);
	public static final CVMBool FALSE=new CVMBool(false);
	
	private CVMBool(boolean value) {
		this.value=value;
	}

	public static CVMBool create(boolean value) {
		return value?TRUE:FALSE;
	}
	
	
	@Override
	public long longValue() {
		return value?1:0;
	}
	
	@Override
	public int estimatedEncodingSize() {
		return 1;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		// Nothing to check. Always valid
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=value?Tag.TRUE:Tag.FALSE;
		return pos;
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		throw new UnsupportedOperationException("Not meaningful to encode raw data for CVMBool");
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append(value?"true":"false");
	}

	@Override
	public void print(StringBuilder sb) {
		sb.append(value?"true":"false");
	}

	@Override
	public Class<?> numericType() {
		return Long.class;
	}

	@Override
	public double doubleValue() {
		return value?1:0;
	}

	public boolean booleanValue() {
		return value;
	}



}
