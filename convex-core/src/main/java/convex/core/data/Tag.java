package convex.core.data;

/**
 * Class containing constant Tag values for CAD3 encoding of Convex values
 * 
 * All of this is critical to the wire format and hash calculation.
 * 
 * This is the gospel. The whole truth, and nothing but the truth.
 * 
 * Hack here at your peril. Changes will break every single database, most immutable Value IDs, and probably your heart.
 */
public class Tag {
	// ========================================
	// Basic constants (0x0x)
	
	public static final byte NULL = (byte) 0x00;

	// ========================================
	// Numeric types (0x1x)
	public static final byte NUMERIC_BASE = (byte) 0x10;
	public static final byte NUMERIC_MASK = (byte) 0xF0;

	
	public static final byte INTEGER = (byte) 0x10; // Arbitrary length integer base
	public static final byte BIG_INTEGER = (byte) 0x19; // Big integer (greater than long range)
	public static final byte DOUBLE = (byte) 0x1d;

    // =========================================
	// Reference types (0x2x)

	public static final byte REF = (byte) 0x20;
	

	// =========================================
	// Strings and Blobs (0x3x)

	public static final byte STRING = (byte) 0x30;
	public static final byte BLOB = (byte) 0x31;
	public static final byte SYMBOL = (byte) 0x32;
	public static final byte KEYWORD = (byte) 0x33;
	
	// Char data type, encoding 2 low bits of length (0x3c - 0x3f). Note: c=1100 binary means 1 byte char, f=1111 binary is 4 bytes
	public static final byte CHAR_BASE = (byte) 0x3c;
	public static final byte CHAR_MASK = (byte) 0x3c;
	public static final byte CHAR_ASCII = (byte) 0x3c; // One byte char == ASCII

	// ==========================================
	// Reserved for future data (0x4x,0x5x,0x6x,0x7x)
	
	// ==========================================
	// Data Structures (0x8x)
	public static final byte VECTOR = (byte) 0x80;
	public static final byte LIST = (byte) 0x81;
	public static final byte MAP = (byte) 0x82;
	public static final byte SET = (byte) 0x83;

	public static final byte INDEX = (byte) 0x84;

	// ==========================================
	// Cryptographic Structures (0x9x)
	public static final byte SIGNED_DATA = (byte) 0x90;
	public static final byte SIGNED_DATA_SHORT = (byte) 0x91;

	// ==========================================
	// Sparsely coded record (0xAx)
	public static final byte SPARSE_RECORD_BASE = (byte) 0xA0;
	
	//=========================
	// Byte Flags (0xBx)

	public static final byte BYTE_FLAG_BASE = (byte) 0xB0;
	public static final byte BYTE_FLAG_MASK = (byte) 0xF0;

	// ==========================================
	// Coded data (0xCx)
	
	public static final byte CODE_BASE = (byte) 0xC0;

	// ==========================================
	// Densely coded record (0xDx)
	public static final byte DENSE_RECORD_BASE = (byte) 0xD0;
	
	//==========================================
	// Extension values (0xEx)

	public static final byte EXTENSION_VALUE_BASE = (byte) 0xE0;

	//===========================================
	// Illegal / reserved for special values (0xFx)
	public static final byte ILLEGAL = (byte) 0xFF;

	
	/**
	 * Get the general category for a given Tag (high hex digit)
	 * @param tag Tag Byte
	 * @return Category e.g. 0xC0 for coded data
	 */
	public static byte category(int tag) {
		return (byte) (tag&0xF0);
	}



}
