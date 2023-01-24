package convex.core.data.prim;

import java.math.BigInteger;

import convex.core.data.BlobBuilder;
import convex.core.data.INumeric;
import convex.core.data.Tag;
import convex.core.exceptions.InvalidDataException;

public class CVMBigInteger extends AInteger {

	private BigInteger data;
	
	@Override
	public CVMLong toLong() {
		// TODO Auto-generated method stub
		return CVMLong.create(big().longValue());
	}

	private BigInteger big() {
		return data;
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
		// TODO Auto-generated method stub
		return 0;
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

}
