package convex.core.util;

/**
 * Some event counters, for debugging and general metrics
 */
public class Counters {

	public static volatile long sendCount = 0;
	public static volatile long beliefMerge = 0;
	public static volatile long etchRead = 0;
	public static volatile long etchWrite = 0;
	public static volatile long applyBlock = 0;
}
