package convex.core.cvm;

/**
 * Class defining tags for CVM CAD3 extension types
 */
public class CVMTag {

	/**
	 * Tag for Convex Address type
	 */
	public static final byte ADDRESS = (byte) 0xEA;
	public static final byte SYNTAX = (byte) 0x88;

	// ==========================================
	// Booleans
	//
	// Implemented as one-byte flags
	
	public static final byte FALSE = (byte) 0xB0;
	public static final byte TRUE = (byte) 0xB1;

	// ==========================================
	// Transactions
	//
	// Implemented as Data Records
	
	public static final byte INVOKE = (byte) 0xD0;

	public static final byte TRANSFER = (byte) 0xD1;

	public static final byte CALL = (byte) 0xD2;

	public static final byte MULTI = (byte) 0xD3;
	
	// ============================================
	//
	// Global State

	public static final byte BELIEF = (byte) 0xD4;
	
	public static final byte STATE = (byte) 0xD5;

	public static final byte BLOCK = (byte) 0xD6;
	
	public static final byte ORDER = (byte) 0xD7;

	public static final byte ACCOUNT_STATUS = (byte) 0xD8;

	public static final byte PEER_STATUS = (byte) 0xD9;

	// Some ops in here
	
	public static final byte RESULT = (byte)0xDD; // transaction result

	public static final byte BLOCK_RESULT = (byte) 0xDE;
	
	// ===============================================
	// CVM Functions
	//
	// Implemented as dense record, 
	
	public static final byte FN = (byte) 0xDF;
	public static final byte FN_NORMAL = (byte) 0xB0; // first byte flag in record defines sub-type

	// ===============================================
	// CVM Ops and Code
	
	/**
	 *  Special Ops as extension value
	 */
	public static final byte OP_SPECIAL = (byte) 0xE5;

	/**
	 * Local is extension value, position on local stack
	 */
	public static final byte OP_LOCAL = (byte) 0xE6;
	
	/**
	 *  CVM Core definitions
	 */
	public static final byte CORE_DEF = (byte) 0xED;

    // ==========================================
	// CVM Ops
	
	// General ops with a single byte flag
	public static final byte OP_CODED = (byte) 0xC0;
	public static final byte OPCODE_CONSTANT = (byte) 0xB0; // Be an 0riginal value
	public static final byte OPCODE_TRY = (byte) 0xBA;      // Be an Attempt
	public static final byte OPCODE_QUERY = (byte) 0xBB;    // Be Back with original state
	public static final byte OPCODE_LAMBDA = (byte) 0xBF;   // Be a Function!

	public static final byte OP_LOOKUP = (byte) 0xC1;
	public static final byte OP_LET = (byte) 0xC2;
	public static final byte OP_LOOP = (byte) 0xC3;

	public static final byte OP_DEF = (byte) 0xCD;
	
	public static final byte OP_DO = (byte)0xDA;     // (do ...)
	public static final byte OP_INVOKE = (byte)0xDB; // (...)   - function Invoke
	public static final byte OP_COND = (byte)0xDC;   // (cond ...)


	



}
