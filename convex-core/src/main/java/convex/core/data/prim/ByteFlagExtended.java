package convex.core.data.prim;

import convex.core.data.ACell;
import convex.core.data.Cells;
import convex.core.data.Tag;
import convex.core.data.util.BlobBuilder;
import convex.core.lang.RT;

/**
 * Class implementing the CAD3 extended byte flags `0xB2` to `0xBF`
 */
public class ByteFlagExtended extends AByteFlag {

	private static final ByteFlagExtended[] cache = new ByteFlagExtended[16];
	
	static {
		for (int i=2; i<16;i++) {
			byte tag=(byte)(Tag.BYTE_FLAG_BASE+i);
			cache[i] = Cells.intern(new ByteFlagExtended(tag));
		}
	}
	
	protected final byte tag;
	
	/**
	 * Private constructor, to enforce singleton instances
	 * @param tag
	 */
	private ByteFlagExtended(byte tag) {
		this.tag=tag;
	}

	/**
	 * Creates an extended byte flag for the given value 2-15
	 * @param value
	 * @return
	 */
	public static ByteFlagExtended create(long value) {
		int m=(int)(value&0xf);
		if (m!=value) return null; // out of range
		return unsafeCreate(m);
	}
	
	static ByteFlagExtended unsafeCreate(int value) {
		return cache[value];
	}

	
	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=tag;
		return pos;
	}

	@Override public boolean isCVMValue() {
		return (tag==Tag.TRUE)||(tag==Tag.FALSE);
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
		return tag==a.getTag();
	}

	@Override
	public boolean print(BlobBuilder sb, long limit) {
		return RT.printCAD3(sb,limit,this);
		
	}

}
