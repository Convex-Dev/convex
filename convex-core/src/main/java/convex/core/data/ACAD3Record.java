package convex.core.data;

import convex.core.data.prim.CVMLong;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.core.util.Utils;

/**
 * Abstract base class for non-CVM CAD3 Records values. 
 * 
 * These look like maps of longs to values in CVM code.
 * 
 * These should always have an encoding since the only way to create them is load from network or storage
 */
public abstract class ACAD3Record extends ARecord<CVMLong,ACell> {

	protected ACAD3Record(byte tag,long count) {
		super(tag,count);
	}
	
	@Override
	public void validateCell() throws InvalidDataException {
		byte cat=Tag.category(tag);
		switch (cat) {
		case Tag.DENSE_RECORD_BASE:
		case Tag.SPARSE_RECORD_BASE:
		  break; // seems OK
		default: throw new InvalidDataException("Bad tag for CAD3 Record: 0x"+Utils.toHexString(tag),this);
		}
	}


	@Override
	public boolean equals(ACell a) {
		if (a==null) return false;
		if (a.getTag()!=tag) return false;
		return encoding.equals(a.getEncoding());
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=tag;
		return encodeRaw(bs,pos);
	}
	
	// subclasses must implement getRefCount and getRef
	
	@Override
	public abstract int getRefCount();
	
	@Override
	public abstract Ref<ACell> getRef(int i);
	
	@Override
	public abstract ACell updateRefs(IRefFunction func);

	@Override
	public boolean isCanonical() {
		// Should be canonical form already!
		return true;
	}

	@Override
	protected ARecord<CVMLong,ACell> toCanonical() {
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
