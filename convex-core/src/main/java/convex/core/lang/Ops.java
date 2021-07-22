package convex.core.lang;

import java.nio.ByteBuffer;

import convex.core.data.ACell;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.ops.Cond;
import convex.core.lang.ops.Constant;
import convex.core.lang.ops.Def;
import convex.core.lang.ops.Do;
import convex.core.lang.ops.Invoke;
import convex.core.lang.ops.Lambda;
import convex.core.lang.ops.Let;
import convex.core.lang.ops.Local;
import convex.core.lang.ops.Lookup;
import convex.core.lang.ops.Query;
import convex.core.lang.ops.Special;
import convex.core.util.Utils;

/**
 * Static utility class for coded operations.
 * 
 * Ops are the fundamental units of code (e.g. as used to implement Actors), and may be
 * effectively considered as "bytecode" for the decentralised state machine.
 */
public class Ops {
	public static final byte CONSTANT = 1;
	public static final byte INVOKE = 2;
	public static final byte DO = 3;
	public static final byte COND = 4;
	public static final byte LOOKUP = 5;
	public static final byte DEF = 6;
	public static final byte LAMBDA = 7;
	public static final byte LET = 8;
	public static final byte QUERY = 9;
	public static final byte LOOP = 10;
	public static final byte LOCAL=11;
	public static final byte SET = 12;
	// public static final byte CALL = 9;
	// public static final byte RETURN = 10;
	
	public static final byte SPECIAL_BASE = 64;

	

	/**
	 * Reads an Op from the given ByteBuffer. Assumes Message tag already read.
	 * 
	 * @param <T> The return type of the Op
	 * @param bb  ByteBuffer
	 * @return Op read from ByteBuffer
	 * @throws BadFormatException If encoding is invalid
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> AOp<T> read(ByteBuffer bb) throws BadFormatException {
		byte opCode = bb.get();
		switch (opCode) {
		case Ops.INVOKE:
			return Invoke.read(bb);
		case Ops.COND:
			return Cond.read(bb);
		case Ops.CONSTANT:
			return Constant.read(bb);
		case Ops.DEF:
			return Def.read(bb);
		case Ops.DO:
			return Do.read(bb);
		case Ops.LOOKUP:
			return Lookup.read(bb);
		// case Ops.CALL: return Call.read(bb);
		case Ops.LAMBDA:
			return (AOp<T>) Lambda.read(bb);
		case Ops.LET:
			return Let.read(bb,false);
		case Ops.QUERY:
			return Query.read(bb);
		case Ops.LOOP:
			return Let.read(bb,true);
		case Ops.LOCAL:
			return Local.read(bb);

		// case Ops.RETURN: return (AOp<T>) Return.read(bb);
		default:
			// range 64-127 is special ops
			if ((opCode&0xC0) == 0x40) {
				Special<T> special=(Special<T>) Special.create(opCode);
				if (special==null) throw new BadFormatException("Bad OpCode for Special value: "+Utils.toHexString((byte)opCode));
				return special;
			}
			
			throw new BadFormatException("Invalide OpCode: " + opCode);
		}
	}
}
