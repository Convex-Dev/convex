package convex.core.data.prim;

import java.math.BigInteger;

import convex.core.data.ABlob;
import convex.core.data.Blob;
import convex.core.data.BlobBuilder;
import convex.core.data.Tag;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Utils;

/**
 * Arbitrary precision integer implementation for the CVM
 */
public class CVMBigInteger extends AInteger {

	private ABlob blob;
	private BigInteger data;
	
	public CVMBigInteger(ABlob blob, BigInteger value) {
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
		// TODO Auto-generated method stub
		return 0;
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
		return Tag.INTEGER;
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
		// TODO Auto-generated method stub
		return false;
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

}
