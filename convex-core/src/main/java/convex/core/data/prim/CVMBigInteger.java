package convex.core.data.prim;

import java.math.BigInteger;

import convex.core.data.ABlob;
import convex.core.data.Blob;
import convex.core.data.BlobBuilder;
import convex.core.data.INumeric;
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
		bs=Utils.trimBigIntegerLeadingBytes(bs);
		return new CVMBigInteger(Blob.wrap(bs),null);
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
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public APrimitive signum() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public INumeric toStandardNumber() {
		return this;
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
		// TODO Needs a separate tag?
		return Tag.LONG;
	}

	@Override
	public int encode(byte[] bs, int pos) {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	protected int encodeRaw(byte[] bs, int pos) {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public boolean print(BlobBuilder sb, long limit) {
		// TODO Auto-generated method stub
		return false;
	}

	/**
	 * Gets the Java BigInteger representing this number
	 * @return Java BigInteger
	 */
	public BigInteger getBigInteger() {
		return big();
	}

}
