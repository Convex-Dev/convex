package convex.core.data.prim;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.BlobBuilder;
import convex.core.data.Strings;
import convex.core.data.Tag;
import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.exceptions.InvalidDataException;

/**
 * Class for CVM Boolean types.
 * 
 * Two canonical values are provided, TRUE and FALSE. No other instances should exist.
 */
public final class CVMBool extends APrimitive {

	private final boolean value;
	
	public static final CVMBool TRUE=new CVMBool(true);
	public static final CVMBool FALSE=new CVMBool(false);

	public static final int MAX_ENCODING_LENGTH = 1;
	
	private CVMBool(boolean value) {
		this.value=value;
	}
	
	@Override
	public AType getType() {
		return Types.BOOLEAN;
	}


	public static CVMBool create(boolean value) {
		return value?TRUE:FALSE;
	}
	
	/**
	 * Get the canonical CVMBool value for true or false
	 * 
	 * @param b Boolean specifying 
	 * @return CVMBool value representing false or true
	 */
	public static CVMBool of(boolean b) {
		return b?TRUE:FALSE;
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
	public boolean print(BlobBuilder bb, long limit) {
		bb.append(value?Strings.TRUE:Strings.FALSE);
		return bb.check(limit);
	}

	@Override
	public double doubleValue() {
		return value?1:0;
	}

	public boolean booleanValue() {
		return value;
	}

	@Override
	public byte getTag() {
		return (value)?Tag.TRUE:Tag.FALSE;
	}

	public static ACell parse(String text) {
		if ("true".equals(text)) return TRUE;
		if ("false".equals(text)) return FALSE;
		return null;
	}

	@Override
	public AString toCVMString(long limit) {
		return value?Strings.TRUE:Strings.FALSE;
	}

}
