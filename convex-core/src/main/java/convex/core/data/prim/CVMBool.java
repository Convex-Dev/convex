package convex.core.data.prim;

import convex.core.cvm.CVMTag;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Strings;
import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.data.util.BlobBuilder;
import convex.core.util.Bits;

/**
 * Class for CVM Boolean types.
 * 
 * Two canonical values are provided, TRUE and FALSE. No other instances should exist.
 */
public final class CVMBool extends AByteFlag {

	private final boolean value;
	
	public static final CVMBool TRUE=Cells.intern(new CVMBool(true));
	public static final CVMBool FALSE=Cells.intern(new CVMBool(false));

	// Salted values for boolean hashcodes
	private static final int TRUE_HASHCODE = Bits.hash32(0xB001C0DE);
	private static final int FALSE_HASHCODE = ~TRUE_HASHCODE;

	// Java String values
	public static final String TRUE_STRING = "true";
	public static final String FALSE_STRING = "false";

	private CVMBool(boolean value) {
		this.value=value;
	}
	
	@Override
	public AType getType() {
		return Types.BOOLEAN;
	}

	public static CVMBool create(boolean value) {
		return value?TRUE:FALSE;
	}
	
	/**
	 * Get the canonical CVMBool value for true or false
	 * 
	 * @param b Boolean specifying 
	 * @return CVMBool value representing false or true
	 */
	public static CVMBool of(boolean b) {
		return b?TRUE:FALSE;
	}
	
	@Override
	public long longValue() {
		return value?1:0;
	}
	
	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=value?CVMTag.TRUE:CVMTag.FALSE;
		return pos;
	}

	@Override
	public boolean print(BlobBuilder bb, long limit) {
		bb.append(value?Strings.TRUE:Strings.FALSE);
		return bb.check(limit);
	}

	@Override
	public double doubleValue() {
		return value?1:0;
	}

	public boolean booleanValue() {
		return value;
	}

	@Override
	public
	final byte getTag() {
		return (value)?CVMTag.TRUE:CVMTag.FALSE;
	}

	public static ACell parse(String text) {
		if ("true".equals(text)) return TRUE;
		if ("false".equals(text)) return FALSE;
		return null;
	}

	@Override
	public AString toCVMString(long limit) {
		return value?Strings.TRUE:Strings.FALSE;
	}
	
	@Override
	public int hashCode() {
		return value?TRUE_HASHCODE:FALSE_HASHCODE;
	}
	
	@Override public boolean equals(ACell a) {
		if (a==null) return false;
		if (this==a) return true;
		return getTag()==a.getTag(); // equivalent to comparing full encoding
	}

	public Blob toBlob() {
		return value?Blob.SINGLE_ONE:Blob.SINGLE_ZERO;
	}

	public ACell not() {
		return value?FALSE:TRUE;
	}
	
	@Override
	public String toString() {
		return value?TRUE_STRING:FALSE_STRING;
	}

}
