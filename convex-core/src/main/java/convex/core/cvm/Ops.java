package convex.core.cvm;

import convex.core.cvm.ops.Constant;
import convex.core.cvm.ops.Lambda;
import convex.core.cvm.ops.Let;
import convex.core.cvm.ops.Lookup;
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
	public static final byte CONSTANT = 0;
	public static final byte TRY = 3;
	public static final byte LET = 4;
	public static final byte LOOP = 5;
	public static final byte DEF = 6;
	public static final byte LOOKUP = 7;
	public static final byte LAMBDA = 8;
	public static final byte QUERY = 9;
	public static final byte LOCAL=10;
	public static final byte SET = 11;
	
	public static final byte SPECIAL = 15;
	// public static final byte CALL = 9;
	// public static final byte RETURN = 10;

	/**
	 * Offset of Op data from tag byte
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
	public static <T extends ACell> AOp<T> read(byte tag, Blob b, int pos) throws BadFormatException {
		byte opCode=b.byteAt(pos+1);
		switch (opCode) {
		case CVMTag.OPCODE_CONSTANT:
			return Constant.read(b,pos);
		case Ops.TRY:
			return Try.read(b,pos);
		case Ops.LOOKUP:
			return Lookup.read(b,pos);
		case Ops.LAMBDA:
			return (AOp<T>) Lambda.read(b,pos);
		case Ops.LET:
			return Let.read(b,pos,false);
		case Ops.QUERY:
			return Query.read(b,pos);
		case Ops.LOOP:
			return Let.read(b,pos,true);
		case Ops.SET:
			return Set.read(b,pos);

		default:
			throw new BadFormatException("Invalide OpCode: " + opCode + " with tag "+tag);
		}
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends ACell> AOp<T> ensureOp(ACell a) {
		if (a==null) return null;
		if (a instanceof AOp) return (AOp<T>)a;
		return null;
	}
}
