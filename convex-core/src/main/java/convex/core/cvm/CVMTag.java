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

	public static final byte STATE = (byte) 0xD5;

	public static final byte ACCOUNT_STATUS = (byte) 0xA1;

	public static final byte PEER_STATUS = (byte) 0xA2;

	public static final byte BELIEF = (byte) 0xAA;

	public static final byte BLOCK = (byte) 0xAB;

	public static final byte ORDER = (byte) 0xAC;

	public static final byte RESULT = (byte)0xAD; // transaction result

	public static final byte BLOCK_RESULT = (byte) 0xAE;
	
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



}
