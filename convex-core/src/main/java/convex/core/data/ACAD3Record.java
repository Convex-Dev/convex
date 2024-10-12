package convex.core.data;

import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;

/**
 * Abstract base class for non-CVM CAD3 Records values. These look like countable sequences to CVM code.
 * 
 * These should always have an encoding since the only way to create them is load from network or storage
 */
public abstract class ACAD3Record extends ASequence<ACell> {

	public ACAD3Record(long count) {
		super(count);
	}

	@Override
	public int estimatedEncodingSize() {
		return encoding.size();
	}

	@Override
	public void validateCell() throws InvalidDataException {
		// TODO Auto-generated method stub
	}

	@Override
	public byte getTag() {
		return encoding.byteAt(0);
	}

	@Override
	public boolean equals(ACell a) {
		if (a==null) return false;
		if (a.getTag()!=getTag()) return false;
		return encoding.equals(a.getEncoding());
	}

	@Override
	public int encode(byte[] bs, int pos) {
		encoding.getBytes(bs, pos);
		return pos+encoding.size();
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		encoding.slice(1).getBytes(bs, pos);
		return pos+encoding.size()-1;
	}

	@Override
	public boolean isCanonical() {
		// Should be canonical form already!
		return true;
	}

	@Override
	protected ACell toCanonical() {
		return this;
	}

	@Override
	public boolean isCVMValue() {
		// Not a CVM value by definition
		return false;
	}

	@Override
	public boolean print(BlobBuilder sb, long limit) {
		return RT.printCAD3(sb, limit, this);
	}

}
