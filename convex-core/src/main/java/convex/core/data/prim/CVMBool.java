package convex.core.data.prim;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.Strings;
import convex.core.data.Tag;
import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Bits;

/**
 * Class for CVM Boolean types.
 * 
 * Two canonical values are provided, TRUE and FALSE. No other instances should exist.
 */
public final class CVMBool extends APrimitive {

	private final boolean value;
	
	public static final CVMBool TRUE=Cells.intern(new CVMBool(true));
	public static final CVMBool FALSE=Cells.intern(new CVMBool(false));

	public static final int MAX_ENCODING_LENGTH = 1;

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
	public <R extends ACell> Ref<R> getRef(int i) {
		throw new IndexOutOfBoundsException(i);
	}

	@Override
	public ACell updateRefs(IRefFunction func) {
		return this;
	}
	
	@Override
	public int getRefCount() {
		// Never any refs
		return 0;
	}
	
	@Override
	public long longValue() {
		return value?1:0;
	}
	
	@Override
	public int estimatedEncodingSize() {
		return 1;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		// Nothing to check. Always valid
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=value?Tag.TRUE:Tag.FALSE;
		return pos;
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
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
	public byte getTag() {
		return (value)?Tag.TRUE:Tag.FALSE;
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
		// Can compare on identity, since only two canonical instances
		return this==a;
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
