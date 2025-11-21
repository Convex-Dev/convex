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
 * Class representing boolean values in the CVM type system.
 * 
 * This class provides canonical TRUE and FALSE instances that are the only valid
 * boolean values in the CVM. The class is immutable and maintains singleton instances
 * for both boolean values to ensure type safety and memory efficiency.
 * 
 * Key features:
 * - Immutable singleton instances
 * - Type-safe boolean operations
 * - Efficient encoding/decoding
 * - Consistent hash codes
 * 
 * Usage example:
 * CVMBool b = CVMBool.of(true);
 * CVMBool result = b.not();
 */
public final class CVMBool extends AByteFlag implements Comparable<CVMBool> {

	private final boolean value;
	
	// Canonical instances
	public static final CVMBool TRUE = Cells.intern(new CVMBool(true));
	public static final CVMBool FALSE = Cells.intern(new CVMBool(false));

	// Canonical hash codes with salt for security
	private static final int CANONICAL_TRUE_HASHCODE = Bits.hash32(0xB001C0DE);
	private static final int CANONICAL_FALSE_HASHCODE = ~CANONICAL_TRUE_HASHCODE;

	// Cached string representations
	public static final String TRUE_STRING = "true";
	public static final String FALSE_STRING = "false";
	
	private CVMBool(boolean value) {
		this.value = value;
	}
	
	@Override
	public AType getType() {
		return Types.BOOLEAN;
	}

	/**
	 * Creates a CVMBool instance from a Java boolean.
	 * 
	 * @param value The boolean value to convert
	 * @return The canonical CVMBool instance representing the value
	 */
	public static CVMBool create(boolean value) {
		return value ? TRUE : FALSE;
	}
	
	/**
	 * Alternative factory method for creating CVMBool instances.
	 * 
	 * @param b The boolean value to convert
	 * @return The canonical CVMBool instance
	 */
	public static CVMBool of(boolean b) {
		return b ? TRUE : FALSE;
	}
	
	/**
	 * Returns true if this represents a true value.
	 * 
	 * @return true if this is the TRUE instance
	 */
	public boolean isTrue() {
		return value;
	}
	
	/**
	 * Returns true if this represents a false value.
	 * 
	 * @return true if this is the FALSE instance
	 */
	public boolean isFalse() {
		return !value;
	}
	
	@Override
	public long longValue() {
		return value ? 1 : 0;
	}
	
	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++] = value ? CVMTag.TRUE : CVMTag.FALSE;
		return pos;
	}

	@Override
	public boolean print(BlobBuilder bb, long limit) {
		bb.append(value ? Strings.TRUE : Strings.FALSE);
		return bb.check(limit);
	}

	@Override
	public double doubleValue() {
		return value ? 1 : 0;
	}

	public boolean booleanValue() {
		return value;
	}

	@Override
	public final byte getTag() {
		return (value) ? CVMTag.TRUE : CVMTag.FALSE;
	}

	/**
	 * Parses a string representation of a boolean value.
	 * 
	 * @param text The text to parse
	 * @return The corresponding CVMBool instance, or null if invalid
	 */
	public static ACell parse(String text) {
		if (TRUE_STRING.equals(text)) return TRUE;
		if (FALSE_STRING.equals(text)) return FALSE;
		return null;
	}

	@Override
	public AString toCVMString(long limit) {
		return value ? Strings.TRUE : Strings.FALSE;
	}
	
	@Override
	public int hashCode() {
		return value ? CANONICAL_TRUE_HASHCODE : CANONICAL_FALSE_HASHCODE;
	}
	
	@Override 
	public boolean equals(ACell a) {
		if (a == null) return false;
		if (this == a) return true;
		return getTag() == a.getTag();
	}

	public Blob toBlob() {
		return value ? Blob.SINGLE_ONE : Blob.SINGLE_ZERO;
	}

	/**
	 * Returns the logical negation of this boolean value.
	 * 
	 * @return The opposite boolean value
	 */
	public CVMBool not() {
		return value ? FALSE : TRUE;
	}
	
	/**
	 * Alternative name for logical negation.
	 * 
	 * @return The opposite boolean value
	 */
	public CVMBool negate() {
		return not();
	}
	
	/**
	 * Performs logical AND operation with another CVMBool.
	 * 
	 * @param other The other boolean value
	 * @return The result of this AND other
	 */
	public CVMBool and(CVMBool other) {
		return value && other.value ? TRUE : FALSE;
	}
	
	/**
	 * Performs logical OR operation with another CVMBool.
	 * 
	 * @param other The other boolean value
	 * @return The result of this OR other
	 */
	public CVMBool or(CVMBool other) {
		return value || other.value ? TRUE : FALSE;
	}
	
	/**
	 * Performs logical XOR operation with another CVMBool.
	 * 
	 * @param other The other boolean value
	 * @return The result of this XOR other
	 */
	public CVMBool xor(CVMBool other) {
		return value ^ other.value ? TRUE : FALSE;
	}
	
	@Override
	public String toString() {
		return value ? TRUE_STRING : FALSE_STRING;
	}
	
	@Override
	public int compareTo(CVMBool other) {
		return Boolean.compare(value, other.value);
	}
}
