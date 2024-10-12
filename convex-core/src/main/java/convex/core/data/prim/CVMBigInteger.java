package convex.core.data.prim;

import java.math.BigInteger;

import convex.core.Constants;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Format;
import convex.core.data.Strings;
import convex.core.data.Tag;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Utils;

/**
 * Arbitrary precision Integer implementation for the CVM.
 * 
 * A CVMBigInteger is a canonical CVM value if and only if it represents a number that cannot be a CVMLong
 */
public final class CVMBigInteger extends AInteger {

	public static final CVMBigInteger MIN_POSITIVE = wrap(new byte[] {0,-128,0,0,0,0,0,0,0});
	public static final CVMBigInteger MIN_NEGATIVE = wrap(new byte[] {-1,127,-1,-1,-1,-1,-1,-1,-1});
	
	public static final BigInteger MIN_POSITIVE_BIG=new BigInteger("9223372036854775808");
	public static final BigInteger MIN_NEGATIVE_BIG=new BigInteger("-9223372036854775809");
	
	protected static final long LONG_BYTELENGTH = 8;
	protected static final long MAX_BYTELENGTH = Constants.MAX_BIG_INTEGER_LENGTH;
	
	// We store the Integer as either a blob or Java BigInteger, and convert lazily on demand
	private Blob blob;
	private BigInteger data;
	
	private CVMBigInteger(Blob blob, BigInteger value) {
		this.blob=blob;
		this.data=value;
	}

	/**
	 * Creates a CVMBigInteger
	 * WARNING: might not be canonical
	 * @param bs Bytes representing BigInteger value. Highest bit assumed to be sign.
	 * @return CVMBigInteger instance or null if not valid
	 */
	public static CVMBigInteger wrap(byte[] bs) {
		byte[] tbs=Utils.trimBigIntegerLeadingBytes(bs);
		if (tbs.length>MAX_BYTELENGTH) return null;
		if (tbs==bs) tbs=tbs.clone(); // Defensive copy just in case
		return new CVMBigInteger(Blob.wrap(tbs),null);
	}
	
	/**
	 * Creates a CVMBigInteger
	 * WARNING: might not be canonical
	 * @param value Java BigInteger
	 * @return CVMBigInteger instance or null if not valid
	 */
	public static CVMBigInteger wrap(BigInteger value) {
		if (Utils.byteLength(value)>MAX_BYTELENGTH) return null; // note bitLength excludes sign bit
		return new CVMBigInteger(null,value);
	}
	
	/**
	 * Create a big integer from a blob representation. Blob can be non-canonical.
	 * @param data Blob data containing twos complement representation
	 * @return Big Integer value or null if not valid.
	 */
	public static CVMBigInteger create(ABlob data) {
		if (data==null) return null;
		data=trimLeadingBytes(data);
		return new CVMBigInteger(data.toFlatBlob(),null);
	}

	
	private static Blob trimLeadingBytes(ABlob data) {
		long n=data.count();
		if (n==0) return Blob.EMPTY;
		if (n==1) return data.toFlatBlob();
		int start=0;
		while (start+1<n) {
			byte bs=data.byteAt(0);
			if ((bs==0)||(bs==-1)) {
				byte bs2=data.byteAt(1);
				if ((bs&0x80)==(bs2&0x80)) {
					start++;
					continue;
				}
			}
			break;
		}
		if (n-start>MAX_BYTELENGTH) return null;
		return data.slice(start,n).toFlatBlob();
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

	protected Blob buildBlob() {
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
	public CVMLong signum() {
		if (data!=null) return CVMLong.forSignum(data.signum());
		return CVMLong.forSignum(blob.byteAt(0));
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
	public
	final byte getTag() {
		// Note we might not be canonical, so this is safest
		return getEncoding().byteAt(0);
	}

	@Override
	public int encode(byte[] bs, int pos) {
		if (!isCanonical()) {
			return getCanonical().encode(bs, pos);
		}
		bs[pos++]=Tag.BIG_INTEGER;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		ABlob b=blob();
		return b.encodeRaw(bs, pos);
	}

	@Override
	public boolean print(BlobBuilder sb, long limit) {
		long blen=byteLength();
		if (blen>limit*2) return false;
		AString s=Strings.create(big().toString());
		if (s.count()>limit) {
			sb.append(s.slice(0, limit));
			return false;
		}
		sb.append(s);
		return true;
	}
	
	@Override
	public int byteLength() {
		// TODO: check value for zero?
		if (blob!=null) return Utils.checkedInt(blob.count());
		return ((data.bitLength())/8)+1;
	}

	@Override
	public String toString() {
		return big().toString();
	}
	
	@Override
	public boolean equals(ACell a) {
		if (a instanceof CVMLong) {
			if (isCanonical()) return false;
			return getCanonical().equals(a);
		}
		if (a instanceof CVMBigInteger) {
			return equals((CVMBigInteger)a);
		}
		return false;
	}
	
	public boolean equals(CVMBigInteger a) {
		if ((data!=null)&&(a.data!=null)) return data.compareTo(a.data)==0;
		return blob().equals(a.blob());
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
		int n=byteLength();
		return (n>8)&&(n<=MAX_BYTELENGTH);
	}
	
	@Override
	protected long calcMemorySize() {	
		return blob().getMemorySize();
	}
	
	@Override
	public boolean isEmbedded() {
		if (memorySize==Format.FULL_EMBEDDED_MEMORY_SIZE) return true;
		return blob().isEmbedded();
	}
	
	@Override
	public AInteger toCanonical() {
		if (isCanonical()) return this;
		ABlob b=blob();
		long v=b.longValue();
		
		// Sign extend
		int shift=(int) (64-(b.count()*8));
		v= (v<<shift)>>shift;
		
		return CVMLong.create(v);
	}
	
	public static CVMBigInteger read(Blob blob, int offset) throws BadFormatException {
		// Read "as if" this was a Blob, although we ignore the tag
		ABlob b=Blobs.read(blob, offset);
		
		if (b==null) throw new BadFormatException("Bad big integer format in read from blob");
		long bc=b.count();
		if (bc<=LONG_BYTELENGTH) {
			throw new BadFormatException("Non-canonical big integer length");
		}
		if (bc>MAX_BYTELENGTH) {
			throw new BadFormatException("Encoding exceeds max big integer length");
		}
		CVMBigInteger result= create(b);
		if (result==null) throw new BadFormatException("Illegal creation of BigInteger from blob");
		if (result.byteLength()!=bc) throw new BadFormatException("Excess leading bytes in BigInteger representation");
		
		// Attach the encoding, will be same length as Blob encoding
		result.attachEncoding(blob.slice(offset,offset+b.getEncodingLength()));
		return result;
	}

	@Override
	public ANumeric abs() {
		BigInteger bi=big();
		if (bi.signum()>=0) return this;
		return wrap(bi.abs());
	}

	@Override
	public AInteger inc() {
		BigInteger bi=big().add(BigInteger.ONE);
		return AInteger.create(bi);
	}

	@Override
	public AInteger dec() {
		BigInteger bi=big().subtract(BigInteger.ONE);
		return AInteger.create(bi);
	}
	
	@Override
	public int compareTo(ANumeric o) {
		if (o instanceof CVMLong) {
			if (!isCanonical()) {
				// Not canonical, therefore inside long range
				return ((CVMLong)getCanonical()).compareTo(o);
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
		return CVMBigInteger.wrap(bi).toCanonical();
	}
	
	@Override
	public AInteger sub(AInteger b) {
		BigInteger bi=big();
		bi=bi.subtract(b.big());
		return CVMBigInteger.wrap(bi).toCanonical();
	}
	
	@Override
	public AInteger negate() {
		BigInteger bi=big();
		bi=bi.negate();
		AInteger neg=CVMBigInteger.wrap(bi);
		if (neg==null) return null; // can theoretically overflow....
		return neg.toCanonical();
	}

	/**
	 * Parses a string as a CVMBigInteger. Might not be canonical.
	 * @param s String containing at least one numeric digit. May have an optional sign.
	 * @return Integer result
	 */
	public static CVMBigInteger parse(String s) {
		BigInteger bi=new BigInteger(s);
		return wrap(bi);
	}

	@Override
	public CVMLong ensureLong() {
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
		return wrap(big().multiply(bb));
	}

	@Override
	public boolean isZero() {
		// Note: won't ever be true on a canonical CVMBigInetger
		return big().signum()==0;
	}

	@Override
	public AInteger mod(AInteger base) {
		BigInteger divisor=base.big();
		int signum=divisor.signum();
		if (signum==0) throw new IllegalArgumentException("mod by zero");;
		if (signum<0) divisor=divisor.negate();
		return AInteger.create(big().mod(divisor));
	}
	
	@Override
	public AInteger rem(AInteger base) {
		BigInteger divisor=base.big();
		int signum=divisor.signum();
		if (signum==0) throw new IllegalArgumentException("rem by zero");;
		return AInteger.create(big().remainder(divisor));
	}

	@Override
	public AInteger div(AInteger base) {
		BigInteger divisor=base.big();
		int signum=divisor.signum();
		if (signum==0) throw new IllegalArgumentException("div by zero");;
		// if (signum<0) divisor=divisor.negate();
		BigInteger d=big().divide(divisor);
		return AInteger.create(d);
	}
	
	@Override
	public AInteger quot(AInteger base) {
		BigInteger divisor=base.big();
		int signum=divisor.signum();
		if (signum==0) throw new IllegalArgumentException("quot by zero");;
		// if (signum<0) divisor=divisor.negate();
		BigInteger d=big().divide(divisor);
		return AInteger.create(d);
	}

	@Override
	public ABlob toBlob() {
		return blob();
	}

	@Override
	public boolean isLong() {
		return !isCanonical();
	}



}
