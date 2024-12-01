package convex.core.cvm;

/**
 * Class defining tags for CVM CAD3 extension types
 */
public class CVMTag {

	
	/**
	 *  Special Ops
	 */
	public static final byte SPECIAL_OP = (byte) 0xE5;

	/**
	 * Tag for Convex Address type
	 */
	public static final byte ADDRESS = (byte) 0xEA;
	
	/**
	 *  CVM Core definitions
	 */
	public static final byte CORE_DEF = (byte) 0xED;

	public static final byte SYNTAX = (byte) 0x88;

	// ==========================================
	// Booleans
	//
	// Implemented as one-byte flags
	
	public static final byte FALSE = (byte) 0xB0;
	public static final byte TRUE = (byte) 0xB1;
	

}
