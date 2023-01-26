package convex.core.data.prim;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.BlobBuilder;
import convex.core.data.Format;
import convex.core.data.Strings;
import convex.core.data.Tag;
import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;

/**
 * Class for CVM long values.
 * 
 * Longs are signed 64-bit integers, and are the primary fixed point integer type on the CVM.
 */
public final class CVMLong extends AInteger {

	private static final int CACHE_SIZE = 256;
	private static final CVMLong[] CACHE= new CVMLong[CACHE_SIZE];

	static {
		for (int i=0; i<256; i++) {
			CACHE[i]=new CVMLong(i);
		}
		ZERO=CACHE[0];
		ONE=CACHE[1];
	}
	
	public static final CVMLong ZERO;
	public static final CVMLong ONE;
	public static final CVMLong MINUS_ONE = CVMLong.create(-1L);
	public static final CVMLong MAX_VALUE = CVMLong.create(Long.MAX_VALUE);
	public static final CVMLong MIN_VALUE = CVMLong.create(Long.MIN_VALUE);
	
	public static final int MAX_ENCODING_LENGTH = 11;
	
	private final long value;
	
	public CVMLong(long value) {
		this.value=value;
	}

	/**
	 * Creates a CVMLong wrapping the given Java long value. Always succeeds.
	 * @param value Java long
	 * @return CVMLong instance.
	 */
	public static CVMLong create(long value) {
		if ((value<CACHE_SIZE)&&(value>=0)) {
			return forByte((byte)value);
		}
		return new CVMLong(value);
	}
	
	public static CVMLong forByte(byte b) {
		return CACHE[0xff&b];
	}
	
	@Override
	public AType getType() {
		return Types.LONG;
	}
	
	@Override
	public long longValue() {
		return value;
	}
	
	@Override
	public CVMLong toLong() {
		return this;
	}

	@Override
	public CVMDouble toDouble() {
		return CVMDouble.create(doubleValue());
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
	public boolean print(BlobBuilder bb, long limit) {
		bb.append(toCVMString(20));
		return bb.check(limit);
	}

	@Override
	public Class<?> numericType() {
		return Long.class;
	}

	@Override
	public double doubleValue() {
		return (double)value;
	}
	
	/**
	 * Parse an Object as a CVM Long, on a best efforts basis
	 * @param o String to parse
	 * @return CVM Long value or null if parse failed
	 */
	public static CVMLong tryParse(Object o) {
		if (o instanceof ACell) {
			return RT.castLong((ACell)o);
		}
		if (o instanceof Long) return CVMLong.create((Long)o);
		if (o instanceof Number) {
			Number n=(Number)o;
			Long lv= n.longValue();
			if (lv.doubleValue()==n.doubleValue()) return CVMLong.create(lv);
		}
		if (o instanceof String) try {
			return parse((String)o);
		} catch (NumberFormatException ne) {
			return null;
		}
		return null;
	}
	
	/**
	 * Parse a String as a CVM Long. Throws an exception if the string is not valid
	 * @param s String to parse
	 * @return CVM Long value
	 * @throws NumberFormatException if the string format is not a valid long
	 */
	public static CVMLong parse(String s) {
		return create(Long.parseLong(s));
	}
	
	@Override
	public byte getTag() {
		return Tag.LONG;
	}

	@Override
	public CVMLong signum() {
		if (value>0) return CVMLong.ONE;
		if (value<0) return CVMLong.MINUS_ONE;
		return CVMLong.ZERO;
	}

	@Override
	public CVMLong toStandardNumber() {
		return this;
	}

	@Override
	public AString toCVMString(long limit) {
		if (limit<1) return null;
		return Strings.create(toString());
	}
	
	@Override
	public String toString() {
		return Long.toString(value);
	}

	public static final CVMLong forSignum(long value) {
		if (value>0) return CVMLong.ONE;
		if (value<0) return CVMLong.MINUS_ONE;
		return CVMLong.ZERO;
	}

	@Override
	public boolean isCanonical() {
		return true; // always canonical
	}

	@Override
	public AInteger abs() {
		if (value>=0) return this;
		if (value==Long.MIN_VALUE) return CVMBigInteger.MIN_POSITIVE;
		return create(-value);
	}

}
