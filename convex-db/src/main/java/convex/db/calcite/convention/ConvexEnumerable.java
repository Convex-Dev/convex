package convex.db.calcite.convention;

import java.util.Iterator;

import convex.core.data.ACell;

/**
 * Enumerable that yields ACell[] rows.
 *
 * <p>This is the result type for ConvexRel.execute(). It provides
 * an iterator over rows represented as ACell arrays, keeping CVM
 * types throughout execution.
 */
public interface ConvexEnumerable extends Iterable<ACell[]> {

	/**
	 * Returns an iterator over the rows.
	 *
	 * @return Iterator of ACell[] rows
	 */
	@Override
	Iterator<ACell[]> iterator();

	/**
	 * Returns an empty enumerable.
	 */
	static ConvexEnumerable empty() {
		return () -> java.util.Collections.emptyIterator();
	}

	/**
	 * Creates an enumerable from an iterable.
	 */
	static ConvexEnumerable of(Iterable<ACell[]> iterable) {
		return iterable::iterator;
	}
}
