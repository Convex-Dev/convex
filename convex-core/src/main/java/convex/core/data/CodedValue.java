package convex.core.data;

import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.InvalidDataException;

public class CodedValue extends ACell {

	private final byte tag;
	private final Ref<ACell> codeRef;
	private final Ref<ACell> valueRef;

	private CodedValue(byte tag, Ref<ACell> code, Ref<ACell> value) {
		this.tag=tag;
		codeRef=code;
		valueRef=value;
	}
	
	@Override
	public int estimatedEncodingSize() {
		return 100;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		// Nothing to do
	}

	@Override
	public byte getTag() {
		return tag;
	}

	@Override
	public boolean equals(ACell a) {
		return Cells.equalsGeneric(this, a);
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=tag;
		return encodeRaw(bs,pos);
	}
	
	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos=codeRef.encode(bs, pos);
		pos=valueRef.encode(bs, pos);
		return pos;
	}

	@Override
	public boolean isCanonical() {
		// TODO Auto-generated method stub
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
		sb.append("#[");
		sb.append(getEncoding().toHexString());
		sb.append("]");
		return sb.check(limit);
	}



}
