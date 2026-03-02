package convex.core.cvm;

import convex.core.cvm.ops.Constant;
import convex.core.data.ACell;

/**
 * Static utility class for coded operations.
 * 
 * Ops are the fundamental units of code (e.g. as used to implement Actors), and may be
 * effectively considered as "bytecode" for the decentralised state machine.
 */
public class Ops {


	@SuppressWarnings("unchecked")
	public static <T extends ACell> AOp<T> ensureOp(ACell a) {
		if (a==null) return null;
		if (a instanceof AOp) return (AOp<T>)a;
		return null;
	}

	/**
	 * Cast any value to an Op. Returns value as a Constant op if not already an Op
	 * @param a Cell to cast to an Op (null results in a Constant nil op)
	 * @return Op instance
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
