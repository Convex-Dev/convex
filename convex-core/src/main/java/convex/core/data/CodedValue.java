package convex.core.data;

import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;
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
	
	public static CodedValue create(int tag, ACell code, ACell value) {
		
		return new CodedValue((byte)tag,Ref.get(code),Ref.get(value));
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
	public void validateStructure() throws InvalidDataException {
		// Nothing to do, any child refs are valid
	}

	@Override
	public byte getTag() {
		return tag;
	}
	
	@Override 
	public int getRefCount() {
		return 2;
	}
	
	@SuppressWarnings("unchecked")
	public <R extends ACell> Ref<R> getRef(int i) {
		switch (i) {
			case 0: return (Ref<R>) codeRef;
			case 1: return (Ref<R>) valueRef;
		}
		throw new IndexOutOfBoundsException(i);
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
	
	@SuppressWarnings("unchecked")
	public CodedValue updateRefs(IRefFunction func) {
		Ref<ACell> nc=func.apply(codeRef);
		Ref<ACell> nv=func.apply(valueRef);
		if ((nc==codeRef)&&(nv==valueRef)) return this;
		
		return new CodedValue(tag,nc,nv);
	}

	@Override
	public boolean print(BlobBuilder sb, long limit) {
		sb.append("#[");
		sb.append(getEncoding().toHexString());
		sb.append("]");
		return sb.check(limit);
	}

	public static CodedValue read(byte tag, Blob b, int pos) throws BadFormatException {
		int epos=pos+1; // skip tag
		
		Ref<ACell> cref=Format.readRef(b, epos);
		epos+=cref.getEncodingLength();
		
		Ref<ACell> vref=Format.readRef(b, epos);
		epos+=vref.getEncodingLength();
		
		CodedValue result=new  CodedValue(tag,cref,vref);
		if (tag==b.byteAtUnchecked(pos)) {
			result.attachEncoding(b.slice(pos,epos));
		}
		return result;
	}


}
