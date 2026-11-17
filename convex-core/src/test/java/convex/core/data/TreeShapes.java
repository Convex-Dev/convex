package convex.core.data;

import java.util.ArrayList;
import java.util.List;

import convex.core.data.prim.CVMLong;

/**
 * Key lists whose hash maps and sets take different tree shapes: roots branching at different
 * hex digits or under different prefixes, children more than one digit below their parent, and
 * leaves with keys outside a tree's prefix.
 */
final class TreeShapes {

	/**
	 * Gets the first integers whose hashes start with the given hex digits and not with the
	 * excluded ones.
	 *
	 * @param prefix Hex digits every hash starts with
	 * @param exclude Hex digits no hash starts with, or null for none
	 * @param n Number of integers
	 * @return Integers in increasing order
	 */
	static List<CVMLong> keys(String prefix, String exclude, int n) {
		ArrayList<CVMLong> result=new ArrayList<>(n);
		for (long i=0; result.size()<n; i++) {
			CVMLong k=CVMLong.create(i);
			String h=k.getHash().toHexString();
			if (!h.startsWith(prefix)) continue;
			if ((exclude!=null)&&h.startsWith(exclude)) continue;
			result.add(k);
		}
		return result;
	}

	@SafeVarargs
	private static List<CVMLong> join(List<CVMLong>... parts) {
		ArrayList<CVMLong> result=new ArrayList<>();
		for (List<CVMLong> p: parts) for (CVMLong k: p) if (!result.contains(k)) result.add(k);
		return result;
	}

	/**
	 * Key lists, each building a tree of a different shape. Some lists share keys, so merges
	 * meet keys in both operands as well as in one.
	 */
	static final List<List<CVMLong>> SHAPES=List.of(
		keys("",null,40), // root at the first digit
		keys("0",null,20), // root at the second digit
		keys("5",null,20), // root at the second digit, another prefix
		keys("00",null,20), // root at the third digit
		join(keys("00",null,20),keys("","0",30)), // child two digits below the root
		join(keys("0",null,20),keys("","0",30)), // child one digit below the root
		keys("","0",3), // leaf outside the prefix of a root at the second digit
		join(keys("00",null,2),keys("0","00",2)), // leaf under the digit of a child two digits below
		join(keys("",null,40),keys("0",null,20)) // root at the first digit, holding a root at the second
	);
}
