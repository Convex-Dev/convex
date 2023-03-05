package convex.core.data.prim;

import java.math.BigInteger;
import java.nio.ByteBuffer;

import convex.core.data.ABlob;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.BlobBuilder;
import convex.core.data.Blobs;
import convex.core.data.Strings;
import convex.core.data.Tag;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Utils;

/**
 * Arbitrary precision Integer implementation for the CVM.
 * 
 * A CVMBigInteger is a canonical CVM value if and only if it represents a number that cannot be a CVMLong
 */
public class CVMBigInteger extends AInteger {

	public static final CVMBigInteger MIN_POSITIVE = create(new byte[] {0,-128,0,0,0,0,0,0,0});
	public static final CVMBigInteger MIN_NEGATIVE = create(new byte[] {-1,127,-1,-1,-1,-1,-1,-1,-1});
	protected static final long LONG_BYTELENGTH = 8;
	
	// We store the Integer as either a blob or Java BigInteger, and convert lazily on demand
	private ABlob blob;
	private BigInteger data;
	
	private CVMBigInteger(ABlob blob, BigInteger value) {
		this.blob=blob;
		this.data=value;
	}

	public static CVMBigInteger create(byte[] bs) {
		byte[] tbs=Utils.trimBigIntegerLeadingBytes(bs);
		if (tbs==bs) tbs=tbs.clone(); // Defensive copy just in case
		return new CVMBigInteger(Blob.wrap(tbs),null);
	}
	
	public static CVMBigInteger create(BigInteger value) {
		return new CVMBigInteger(null,value);
	}
	
	/**
	 * Create a big integer from a valid blob representation. Can be non-canonical.
	 * @param data Blob data containing minimal BigInteger twos complement representation
	 * @return Big Integer value or null if not valid.
	 */
	public static CVMBigInteger create(ABlob data) {
		long n=data.count();
		if (n==0) return null;
		if (n>1) {
			byte bs=data.byteAt(0);
			if ((bs==0)||(bs==-1)) {
				byte bs2=data.byteAt(1);
				if ((bs&0x80)==(bs2&0x80)) return null; // excess leading byte not allowed
			}
		}
		return new CVMBigInteger(data,null);
	}

	
	@Override
	public CVMLong toLong() {
		return CVMLong.create(longValue());
	}
	

	@Override
	public long longValue() {
		if (blob!=null) return blob.longValue();
		return data.longValue();
	}

	@Override
	public BigInteger big() {
		if (data==null) data=buildBigInteger();
		return data;
	}
	
	protected ABlob blob() {
		if (blob==null) blob=buildBlob();
		return blob;
	}

	protected ABlob buildBlob() {
		return Blob.wrap(data.toByteArray());
	}

	protected BigInteger buildBigInteger() {
		long n=blob.count();
		if (n==0) return BigInteger.ZERO;
		return new BigInteger(blob.getBytes());
	}

	@Override
	public CVMDouble toDouble() {
		return CVMDouble.create(doubleValue());
	}

	@Override
	public Class<?> numericType() {
		// TODO Specific INTEGER type
		return Long.class;
	}

	@Override
	public APrimitive signum() {
		if (data!=null) return CVMLong.forSignum(data.signum());
		return CVMLong.forSignum(blob.byteAt(0));
	}

	@Override
	public ANumeric toStandardNumber() {
		if (isCanonical()) return this;
		return (ANumeric) getCanonical();
	}

	@Override
	public int estimatedEncodingSize() {
		if (blob!=null) return blob.estimatedEncodingSize();
		return (int) Math.min(Blob.MAX_ENCODING_LENGTH,byteLength()+10);
	}


	@Override
	public double doubleValue() {
		return big().doubleValue();
	}

	@Override
	public void validateCell() throws InvalidDataException {
		// TODO Auto-generated method stub

	}

	@Override
	public byte getTag() {
		return isCanonical()?Tag.INTEGER:Tag.LONG;
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.INTEGER;
		return encodeRaw(bs,pos);
	}

	@Override
	protected int encodeRaw(byte[] bs, int pos) {
		ABlob b=blob();
		return b.encodeRaw(bs, pos);
	}

	@Override
	public boolean print(BlobBuilder sb, long limit) {
		long blen=byteLength();
		if (blen>limit*2) return false;
		AString s=Strings.create(big().toString());
		if (s.count()>limit) return false;
		sb.append(s);
		return true;
	}
	
	@Override
	public long byteLength() {
		if (blob!=null) return blob.count();
		return ((data.bitLength())/8)+1;
	}

	@Override
	public String toString() {
		return big().toString();
	}

	/**
	 * Gets the Java BigInteger representing this number
	 * @return Java BigInteger
	 */
	public BigInteger getBigInteger() {
		return big();
	}

	@Override
	public boolean isCanonical() {
		return (blob().count()>8);
	}
	
	@Override
	public AInteger toCanonical() {
		if (isCanonical()) return this;
		return CVMLong.create(big().longValue());
	}

	public static CVMBigInteger read(ByteBuffer bb) throws BadFormatException {
		ABlob b=Blobs.read(bb);
		if (b.count()<=8) throw new BadFormatException("Non-canonical size of big integer");
		CVMBigInteger bi= create(b);
		if (bi==null) throw new BadFormatException("Invalid blob representation of big integer");
		return bi;
	}

	@Override
	public APrimitive abs() {
		BigInteger bi=big();
		if (bi.signum()>=0) return this;
		return create(bi.abs());
	}

	@Override
	public AInteger inc() {
		BigInteger bi=big().add(BigInteger.ONE);
		return (AInteger) create(bi).getCanonical();
	}

	@Override
	public AInteger dec() {
		BigInteger bi=big().subtract(BigInteger.ONE);
		return (AInteger) create(bi).getCanonical();
	}
	
	@Override
	public int compareTo(ANumeric o) {
		if (o instanceof CVMLong) {
			if (!isCanonical()) {
				// Not canonical, therefore inside long range
				return Long.compare(longValue(), o.longValue());
			}
			if (big().compareTo(MIN_POSITIVE.big())>=0) return 1; // Big integer above long range
			return -1; // big integer must be more neative than Long range
		};
		if (o instanceof CVMBigInteger) return big().compareTo(((CVMBigInteger)o).big());
		return Double.compare(doubleValue(), o.doubleValue());
	}
	
	@Override
	public AInteger add(AInteger a) {
		
		BigInteger bi=big();
		bi=bi.add(a.big());
		return CVMBigInteger.create(bi).toCanonical();
	}
	
	@Override
	public AInteger sub(AInteger b) {
		BigInteger bi=big();
		bi=bi.subtract(b.big());
		return CVMBigInteger.create(bi).toCanonical();
	}
	
	@Override
	public AInteger negate() {
		BigInteger bi=big();
		bi=bi.negate();
		return CVMBigInteger.create(bi).toCanonical();
	}

	/**
	 * Parses a string as a CVMBigInteger. Might not be canonical.
	 * @param s String containing at least one numeric digit. May have an optional sign.
	 * @return Integer result
	 */
	public static CVMBigInteger parse(String s) {
		BigInteger bi=new BigInteger(s);
		return create(bi);
	}

	@Override
	public CVMLong asLongInteger() {
		if (byteLength()>LONG_BYTELENGTH) return null;
		return (CVMLong)getCanonical();
	}

	@Override
	public ANumeric multiply(ANumeric b) {
		if (b instanceof CVMDouble) {
			return CVMDouble.create(big().doubleValue()*b.doubleValue());
		} 
		
		BigInteger bb;
		if (b instanceof CVMLong) {
			bb=BigInteger.valueOf(b.longValue());
		} else {
			bb=((CVMBigInteger)b).getBigInteger();
		}
		return create(big().multiply(bb));
	}

}
