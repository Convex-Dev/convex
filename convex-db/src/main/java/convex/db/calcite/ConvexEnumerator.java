package convex.db.calcite;

import java.util.NoSuchElementException;

import org.apache.calcite.linq4j.Enumerator;

import convex.core.data.ACell;

/**
 * Abstract base for enumerators over ACell[] rows in the ConvexConvention pipeline.
 *
 * <p>Implements Calcite's {@code Enumerator} interface with CVM-native types.
 * Concrete implementations provide different access strategies:
 * <ul>
 *   <li>{@link ConvexTableEnumerator} — full table scan from lattice Index
 *   <li>Future: range scan, index lookup, streaming, etc.
 * </ul>
 */
public abstract class ConvexEnumerator implements Enumerator<ACell[]> {

	protected ACell[] currentRow;

	@Override
	public ACell[] current() {
		if (currentRow == null) {
			throw new NoSuchElementException();
		}
		return currentRow;
	}

	@Override
	public void close() {
		// Default: nothing to close for in-memory lattice data
	}
}
