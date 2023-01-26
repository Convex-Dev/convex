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
 */
public class CVMBigInteger extends AInteger {

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
		return CVMLong.create(big().longValue());
	}

	protected BigInteger big() {
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
	public long longValue() {
		return big().longValue();
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
	
	/**
	 * Number of bytes in minimal representation of this Integer
	 * @return Number of bytes
	 */
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
	public ANumeric toCanonical() {
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

}
