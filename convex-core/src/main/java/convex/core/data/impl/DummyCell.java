package convex.core.data.impl;

import convex.core.data.ACell;
import convex.core.data.Tag;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.InvalidDataException;

/**
 * A dummy cell implementation, not a valid CVM value
 */
public class DummyCell extends ACell {

	@Override
	public int estimatedEncodingSize() {
		return 1;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		throw new InvalidDataException("This is a dummy value",this);
	}

	@Override
	public byte getTag() {
		return Tag.ILLEGAL;
	}

	@Override
	public boolean equals(ACell a) {
		return this==a;
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.ILLEGAL;
		return pos;
	}
	
	@Override
	public int encodeRaw(byte[] bs, int pos) {
		// Nothing to encode
		return pos;
	}

	@Override
	public boolean isCanonical() {
		return true;
	}

	@Override
	protected ACell toCanonical() {
		return this;
	}

	@Override
	public boolean isCVMValue() {
		return false;
	}

	@Override
	public boolean print(BlobBuilder sb, long limit) {
		sb.append("DUMMY");
		return sb.check(limit);
	}
}
