package convex.core.data.prim;

import convex.core.data.Format;
import convex.core.data.Tag;
import convex.core.exceptions.InvalidDataException;

public class CVMLong extends APrimitive {

	private final long value;
	
	public CVMLong(long value) {
		this.value=value;
	}

	public static CVMLong create(long value) {
		return new CVMLong(value);
	}
	
	@Override
	public long longValue() {
		return value;
	}
	
	@Override
	public int estimatedEncodingSize() {
		return 1+Format.MAX_VLC_LONG_LENGTH;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		// Nothing to check. Always valid
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.LONG;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		return Format.writeVLCLong(bs, pos, value);
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append(value);
	}

	@Override
	public void print(StringBuilder sb) {
		sb.append(value);
	}



}
