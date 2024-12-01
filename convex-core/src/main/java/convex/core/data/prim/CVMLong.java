package convex.core.data.prim;

import java.math.BigInteger;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.Tag;
import convex.core.data.impl.LongBlob;
import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.core.util.Utils;

/**
 * Class for CVM long values.
 * 
 * Longs are signed 64-bit integers, and are the primary fixed point integer type on the CVM.
 */
public final class CVMLong extends AInteger {

	// We implement a 256-entry cache for common small values 0-255
	// i.e. the valid Byte range
	private static final int CACHE_SIZE = 256;
	private static final CVMLong[] CACHE= new CVMLong[CACHE_SIZE];

	static {
		for (int i=0; i<256; i++) {
			CACHE[i]=Cells.intern(new CVMLong(i));
		}
		ZERO=CACHE[0];
		ONE=CACHE[1];
	}
	
	public static final CVMLong ZERO;
	public static final CVMLong ONE;
	public static final CVMLong MINUS_ONE = Cells.intern(CVMLong.create(-1L));
	public static final CVMLong MAX_VALUE = Cells.intern(CVMLong.create(Long.MAX_VALUE));
	public static final CVMLong MIN_VALUE = Cells.intern(CVMLong.create(Long.MIN_VALUE));
	
	public static final int MAX_ENCODING_LENGTH = 9;
	
	private final long value;
	
	public CVMLong(long value) {
		this.value=value;
		this.memorySize=Format.FULL_EMBEDDED_MEMORY_SIZE;
	}

	/**
	 * Creates a CVMLong wrapping the given Java long value. Always succeeds.
	 * @param value Java long
	 * @return CVMLong instance.
	 */
	public static CVMLong create(long value) {
		if ((value<CACHE_SIZE)&&(value>=0)) {
			return forByte((int)value);
		}
		return new CVMLong(value);
	}
	
	/**
	 * Creates a CVMLong wrapping the given Java long value. Always succeeds.
	 * @param value Java long
	 * @return CVMLong instance, or null if value was null
	 */
	public static CVMLong create(Long value) {
		if (value==null) return null;
		return create(value.longValue());
	}
	
	/**
	 * Gets the CVMLong representing an unsigned byte value
	 * @param b Byte to convert to CVMLong (will be interpreted as unsigned)
	 * @return CVMLong value
	 */
	public static CVMLong forByte(int b) {
		return CACHE[0xff&b];
	}
	
	@Override
	public AType getType() {
		return Types.INTEGER;
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
		return 1+Format.MAX_VLQ_LONG_LENGTH;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		// Nothing to check. Always valid
	}

	@Override
	public int encode(byte[] bs, int pos) {
		int numBytes=Format.getLongLength(value);
		bs[pos++]=(byte) (Tag.INTEGER+numBytes);
		return encodeRaw(bs,pos,numBytes);
	}
	
	@Override
	public int encodeRaw(byte[] bs, int pos) {
		int numBytes=Format.getLongLength(value);
		return encodeRaw(bs,pos,numBytes);
	}

	private int encodeRaw(byte[] bs, int pos, int numBytes) {
		for (int i=0; i<numBytes; i++) {
			bs[pos+i]=(byte)(value>>((numBytes-1-i)<<3));
		}
		return pos+numBytes;
	}
	
	public static CVMLong read(byte tag, Blob blob, int offset) throws BadFormatException {
		int numBytes=tag-Tag.INTEGER;
		if (numBytes==0) return ZERO;
		long v=Format.readLong(blob,offset+1,numBytes);
		
		long end=offset+1+numBytes;
		CVMLong result= create(v);
		if (result.encoding==null) {
			// we likely already have a valid encoding if cached!
			result.attachEncoding(blob.slice(offset,end));
		}
		return result;
	}

	@Override
	public boolean print(BlobBuilder bb, long limit) {
		bb.appendLongString(value);
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
	 * @param o Object to parse
	 * @return CVM Long value, or null if parse failed
	 */
	public static CVMLong parse(Object o) {
		if (o instanceof ACell) {
			CVMLong v= RT.ensureLong((ACell)o);
			if (v!=null) return v;
			if (o instanceof AString) {
				return parse(o.toString());
			}
		}
		if (o instanceof Number) {
			if (o instanceof Long) return CVMLong.create(((Long)o).longValue());
			Number n=(Number)o;
			Long lv= n.longValue();
			if (lv.doubleValue()==n.doubleValue()) return CVMLong.create(lv.longValue());
		}
		if (o instanceof String) {
			return parse((String)o);
		}
		return null;
	}
	
	/**
	 * Parse a String as a CVM Long. Throws an exception if the string is not valid
	 * @param s String to parse
	 * @return CVM Long value, or null if not convertible
	 */
	public static CVMLong parse(String s) {
		try {
			return create(Long.parseLong(s));
		} catch (NumberFormatException e) {
			return null;
		}
	}
	
	@Override
	public final byte getTag() {
		if (encoding!=null) {
			return encoding.byteAt(0);
		}
		return (byte) (Tag.INTEGER+Format.getLongLength(value));
	}

	@Override
	public CVMLong signum() {
		if (value>0) return CVMLong.ONE;
		if (value<0) return CVMLong.MINUS_ONE;
		return CVMLong.ZERO;
	}
	
	@Override
	public String toString() {
		return Long.toString(value);
	}
	
	@Override
	public boolean equals(ACell c) {
		if (c==this) return true;
		if (c instanceof CVMLong) {
			return equals((CVMLong)c);
		}
		if (c instanceof CVMBigInteger) {
			CVMBigInteger bi=(CVMBigInteger) c;
			if (c.isCanonical()) return false;
			return value==bi.longValue();
		}
		return false;
	}
	
	public boolean equals(CVMLong c) {
		return c.value==this.value;
	}

	/**
	 * Gets a CVMLong representing the signum of the given value
	 * @param value Value to test for signum
	 * @return 1, 0 or -1 as a CVM Long
	 */
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

	@Override
	public AInteger inc() {
		if (value==Long.MAX_VALUE) return CVMBigInteger.MIN_POSITIVE;
		return create(value+1);
	}

	@Override
	public AInteger dec() {
		if (value==Long.MIN_VALUE) return CVMBigInteger.MIN_NEGATIVE;
		return create(value-1);
	}

	@Override
	public int compareTo(ANumeric o) {
		if (o instanceof CVMLong) return Long.compare(value, o.longValue());
		if (o instanceof CVMBigInteger) return -((CVMBigInteger)o).compareTo(this);
		return Double.compare(doubleValue(), o.doubleValue());
	}
	
	@Override
	public CVMLong ensureLong() {
		return this;
	}

	@Override
	public int byteLength() {
		return Utils.byteLength(value);
	}

	@Override
	public AInteger add(AInteger a) {
		if (a instanceof CVMLong)  return add((CVMLong)a);
		return a.add(this); // OK since commutative, and isn't a CVMLong
	}
	
	public AInteger add(CVMLong b) {
		long av=value;
		long bv=b.value;
		if (bv==0) return this;
		if (av==0) return b;
		
		long r=av+bv;
		if ((av>0)^(bv>0)) return CVMLong.create(r); // opposite signs can't overflow
		if ((av>0)&&(bv>0)&&(r>0)) return CVMLong.create(r); // no overflow when adding positives
		if ((av<0)&&(bv<0)&&(r<0)) return CVMLong.create(r); // no overflow when adding negatives
		
		BigInteger bi=BigInteger.valueOf(av);
		bi=bi.add(BigInteger.valueOf(bv));
		return CVMBigInteger.wrap(bi).toCanonical();
	}
	
	@Override
	public AInteger sub(AInteger a) {
		if (a instanceof CVMLong)  return sub((CVMLong)a);
		BigInteger bi=big();
		bi=bi.subtract(a.big());
		return CVMBigInteger.wrap(bi).toCanonical();
	}
	
	public AInteger sub(CVMLong b) {
		long av=value;
		long bv=b.value;
		if (bv==0) return this;
		
		// TODO: fast paths for pure longs
		//long r=av+bv;
		//if ((av>0)^(bv>0)) return CVMLong.create(r); // opposite signs can't overflow
		//if ((av>0)&&(bv>0)&&(r>0)) return CVMLong.create(r); // no overflow when adding positives
		//if ((av<0)&&(bv<0)&&(r<0)) return CVMLong.create(r); // no overflow when adding negatives
		
		BigInteger bi=BigInteger.valueOf(av);
		bi=bi.subtract(BigInteger.valueOf(bv));
		return CVMBigInteger.wrap(bi).toCanonical();
	}

	@Override
	public BigInteger big() {
		return BigInteger.valueOf(value);
	}

	@Override
	public AInteger negate() {
		if (value==Long.MIN_VALUE) return CVMBigInteger.MIN_POSITIVE;
		if (value==0) return ZERO;
		return create(-value);
	}

	@Override
	public ANumeric multiply(ANumeric b) {
		if (b instanceof CVMLong) {
			long av=value;
			long bv=((CVMLong) b).value;
			long lo=av*bv;
			long hi=Math.multiplyHigh(av,bv);
			if ((hi==0)&&(lo>=0)) return CVMLong.create(lo);
			if ((hi==-1)&&(lo<0)) return CVMLong.create(lo);
			BigInteger result= BigInteger.valueOf(av).multiply(BigInteger.valueOf(bv));
			return CVMBigInteger.wrap(result);
		}
		return b.multiply(this);
	}

	@Override
	public boolean isZero() {
		return value==0;
	}

	@Override
	public AInteger mod(AInteger base) {
		if (base instanceof CVMLong) return mod((CVMLong)base);
		return AInteger.create(big().mod(base.big()));
	}
	
	public CVMLong mod(CVMLong base) {
		long num=value;
		long denom=base.value;
		if (denom==0) throw new IllegalArgumentException("mod by zero");
		long m = num % denom;
		if (m<0) m+=Math.abs(denom); // Correct for Euclidean modular function
		return CVMLong.create(m);
	}

	@Override
	public AInteger div(AInteger base) {
		if (base instanceof CVMLong) return div((CVMLong)base);
		return null;
	}
	
	public CVMLong div(CVMLong base) {
		long num=value;
		long denom=base.value;
		if (denom==0) throw new IllegalArgumentException("div by zero");;
		if (num<0) num-=(denom-1); // Correct for Euclidean modular function
		long d = num / denom;
		
		return CVMLong.create(d);
	}
	
	@Override
	public AInteger rem(AInteger base) {
		if (base instanceof CVMLong) return rem((CVMLong)base);
		return null;
	}
	
	public CVMLong rem(CVMLong base) {
		long num=value;
		long denom=base.value;
		if (denom==0) throw new IllegalArgumentException("rem by zero");;

		long r = num % denom;
		
		return CVMLong.create(r);
	}
	
	@Override
	public AInteger quot(AInteger base) {
		if (base instanceof CVMLong) return quot((CVMLong)base);
		return null;
	}
	
	public CVMLong quot(CVMLong base) {
		long num=value;
		long denom=base.value;
		if (denom==0) throw new IllegalArgumentException("quot by zero");;
		long d = num / denom;
		// Correct for Euclidean modular function
		return CVMLong.create(d);
	}

	@Override
	public AInteger toCanonical() {
		// Always canonical
		return this;
	}

	@Override
	public ABlob toBlob() {
		long n=byteLength();
		ABlob result= LongBlob.create(value);
		if (n<8) {
			result=result.slice(8-n);
		}
		return result;
	}

	@Override
	public boolean isLong() {
		return true;
	}



}
