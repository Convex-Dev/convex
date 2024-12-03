package convex.core.data.prim;

import convex.core.cvm.CVMTag;
import convex.core.data.ACell;
import convex.core.data.Cells;
import convex.core.data.Tag;
import convex.core.data.util.BlobBuilder;
import convex.core.lang.RT;

/**
 * Class implementing the CAD3 extended byte flags `0xB2` to `0xBF`
 */
public class ByteFlag extends AByteFlag {

	private static final ByteFlag[] cache = new ByteFlag[16];
	
	static {
		for (int i=0; i<16;i++) {
			byte tag=(byte)(Tag.BYTE_FLAG_BASE+i);
			cache[i] = Cells.intern(new ByteFlag(tag));
		}
	}
	
	protected final byte tag;
	
	/**
	 * Private constructor, to enforce singleton instances
	 * @param tag
	 */
	public ByteFlag(byte tag) {
		this.tag=tag;
	}

	/**
	 * Creates an extended byte flag for the given value 0-15
	 * @param value
	 * @return
	 */
	public static ByteFlag create(long value) {
		int m=(int)(value&0xf);
		if (m!=value) return null; // out of range
		return unsafeCreate(m);
	}
	
	public static ByteFlag forTag(byte tag) {
		if ((tag & 0xF0)!=Tag.BYTE_FLAG_BASE) return null;
		return new ByteFlag(tag);
	}
	
	static ByteFlag unsafeCreate(int value) {
		return cache[value];
	}

	
	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=tag;
		return pos;
	}

	@Override public boolean isCVMValue() {
		return (tag==CVMTag.TRUE)||(tag==CVMTag.FALSE);
	}
	
	@Override
	public long longValue() {
		return ((long)tag)-Tag.BYTE_FLAG_BASE;
	}

	@Override
	public double doubleValue() {
		return longValue();
	}

	@Override
	public byte getTag() {
		return tag;
	}

	@Override
	public boolean equals(ACell a) {
		if (a==null) return false;
		return tag==a.getTag(); // equivalent to comparing full encoding
	}

	@Override
	public boolean print(BlobBuilder sb, long limit) {
		return RT.printCAD3(sb,limit,this);
		
	}



}
