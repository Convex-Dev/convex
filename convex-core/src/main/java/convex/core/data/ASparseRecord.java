package convex.core.data;

/**
 * Base class for sparse records, implementing CAD3 tags 0xA0-0xAF
 */
public abstract class ASparseRecord extends ARecord<ACell,ACell> {

	protected final long inclusion;

	protected ASparseRecord(byte tag, long mask) {
		super(tag,Long.bitCount(mask));
		this.inclusion=mask;
		if (mask<0) throw new IllegalArgumentException("Negative mask");
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=tag;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		long n=count();
		pos=Format.writeVLQCount(bs, pos, n);
		for (int i=0; i<n; i++) {
			pos=getRef(i).encode(bs, pos);
		}
		return pos;
	}
}
