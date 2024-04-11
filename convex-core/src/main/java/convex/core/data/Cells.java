package convex.core.data;

import java.lang.reflect.Array;

/**
 * Static utility class for dealing with cells
 */
public class Cells {

	/**
	 * An empty Java array of cells
	 */
	public static final ACell[] EMPTY_ARRAY = new ACell[0];

	/**
	 * Equality method allowing for nulls
	 *
	 * @param a First value
	 * @param b Second value
	 * @return true if arguments are equal, false otherwise
	 */
	public static boolean equals(ACell a, ACell b) {
		if (a == b) return true;
		if (a == null) return false; // b can't be null because of above line
		return a.equals(b); // fall back to ACell equality
	}

	/**
	 * Converts any array to an ACell[] array. Elements must be Cells.
	 *
	 * @param anyArray Array to convert
	 * @return ACell[] array
	 */
	public static ACell[] toCellArray(Object anyArray) {
		int n = Array.getLength(anyArray);
		ACell[] result = new ACell[n];
		for (int i = 0; i < n; i++) {
			result[i] = (ACell) Array.get(anyArray, i);
		}
		return result;
	}

}
