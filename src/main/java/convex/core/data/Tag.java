package convex.core.data;

/**
 * Class containing constant Tag values.
 * 
 * All of this is critical to the wire format and hash calculation.
 * 
 * This is the gospel. The whole truth, and nothing but the truth.
 * 
 * Hack here at your peril.
 */
public class Tag {
	// Basic Types: Primitive values and numerics
	// we might add unsigned primitives at some point?
	public static final byte NULL = (byte) 0x00;
	public static final byte BYTE = (byte) 0x03;
	public static final byte SHORT = (byte) 0x05;
	public static final byte INT = (byte) 0x07;
	public static final byte LONG = (byte) 0x09;

	public static final byte BIG_INTEGER = (byte) 0x0a; // Arbitrary length integer
	public static final byte CHAR = (byte) 0x0c;
	public static final byte DOUBLE = (byte) 0x0d;
	public static final byte FLOAT = (byte) 0x0f;
	public static final byte BIG_DECIMAL = (byte) 0x0e; // E notation precise decimal

	// Amounts of tokens
	// Note: Amounts use the low 4 bits of the tag for decimal scale factor
	public static final byte AMOUNT = (byte) 0x10; // Financial amount

	// crypto and security primitives
	public static final byte REF = (byte) 0x20;
	public static final byte ADDRESS = (byte) 0x21;
	public static final byte SIGNATURE = (byte) 0x22;
	public static final byte HASH = (byte) 0x24;
	public static final byte ACCOUNT_KEY = (byte) 0x2a;

	// Standard supported object data types
	public static final byte STRING = (byte) 0x30;
	public static final byte BLOB = (byte) 0x31;
	public static final byte SYMBOL = (byte) 0x32;
	public static final byte KEYWORD = (byte) 0x33;

	// data type tags beyond this point

	// general purpose data structures
	public static final byte VECTOR = (byte) 0x80;
	public static final byte LIST = (byte) 0x81;
	public static final byte MAP = (byte) 0x82;
	public static final byte SET = (byte) 0x83;

	public static final byte BLOBMAP = (byte) 0x84;

	public static final byte MAP_ENTRY = (byte) 0x87;
	public static final byte SYNTAX = (byte) 0x88;

	// special data structure
	public static final byte SIGNED_DATA = (byte) 0x90;

	// Record data structures
	public static final byte STATE = (byte) 0xA0;
	public static final byte BELIEF = (byte) 0xAA;
	public static final byte BLOCK = (byte) 0xAB;
	public static final byte ORDER = (byte) 0xAC;
	public static final byte RESULT = (byte)0xAD; // transaction result
	public static final byte BLOCK_RESULT = (byte) 0xAE;

	public static final byte FALSE = (byte) 0xB0;
	public static final byte TRUE = (byte) 0xB1;

	// Control structures
	public static final byte COMMAND = (byte) 0xC0;
	public static final byte ACCOUNT_STATUS = (byte) 0xC1;
	public static final byte PEER_STATUS = (byte) 0xC2;

	// Code
	public static final byte OP = (byte) 0xCC;
	public static final byte CORE_DEF = (byte) 0xCD;
	public static final byte EXPANDER = (byte) 0xCE;
	public static final byte FN = (byte) 0xCF;
	public static final byte FN_MULTI = (byte) 0xCB;
	
	// transaction types
	public static final byte INVOKE = (byte) 0xD0;
	public static final byte TRANSFER = (byte) 0xD1;
	public static final byte CALL = (byte) 0xD2;

	// F? Illegal / reserved
	public static final byte ILLEGAL = (byte) 0xFF;

}
