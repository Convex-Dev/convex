package convex.core.cvm;

import convex.core.cvm.ops.Constant;
import convex.core.cvm.ops.Lambda;
import convex.core.cvm.ops.Query;
import convex.core.cvm.ops.Set;
import convex.core.cvm.ops.Try;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.exceptions.BadFormatException;

/**
 * Static utility class for coded operations.
 * 
 * Ops are the fundamental units of code (e.g. as used to implement Actors), and may be
 * effectively considered as "bytecode" for the decentralised state machine.
 */
public class Ops {


	/**
	 * Offset of Op value from tag byte in coded op
	 */
	public static final int OP_DATA_OFFSET=2;

	/**
	 * Reads an Op from the given Blob. Assumes tag specifying an Op already read.
	 * @param tag 
	 * 
	 * @param <T> The return type of the Op
	 * @param b Blob to read from
	 * @param pos Start position in Blob (location of tag byte)
	 * @return New decoded instance
	 * @throws BadFormatException In the event of any encoding error
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> AOp<T> readCodedOp(byte tag, Blob b, int pos) throws BadFormatException {
		// Read the byte containing the flag directly
		byte opCode=b.byteAt(pos+1);
		
		switch (opCode) {
		case CVMTag.OPCODE_CONSTANT:
			return Constant.read(b,pos);
		case CVMTag.OPCODE_TRY:
			return Try.read(b,pos);
		case CVMTag.OPCODE_LAMBDA:
			return (AOp<T>) Lambda.read(b,pos); 
		case CVMTag.OPCODE_QUERY:
			return Query.read(b,pos);

		// These tags mean we must have a Long integer, which resolves to a local Set operation
		case 0x10: case 0x11: case 0x12: case 0x13: case 0x14: case 0x15: case 0x16: case 0x17: case 0x18:
			return Set.read(b,pos);

		default:
			throw new BadFormatException("Invalide OpCode: " + opCode);
		}
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends ACell> AOp<T> ensureOp(ACell a) {
		if (a==null) return null;
		if (a instanceof AOp) return (AOp<T>)a;
		return null;
	}

	/**
	 * Cast any value to an Op. Returns value as a Constant op if not already an Op
	 * @param aOp
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> AOp<T> castOp(ACell a) {
		if (a==null) return Constant.nil();
		if (a instanceof AOp) {
			return (AOp<T>)a;
		} else {
			return (AOp<T>) Constant.create(a);
		}
	}
}
