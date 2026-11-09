package convex.lattice.cursor;

import java.io.IOException;

import convex.core.data.ACell;

/**
 * Writes a value's cells to a store, returning an equal value whose cells are
 * retained there, as {@code LatticeHost::persist} does. Cursors and components
 * persist through one without knowing the store.
 *
 * <p>Whoever holds the value must switch to the one returned. On an Etch store
 * persisting builds new cells whose children are soft references, and the cells it
 * was given keep strong references to theirs. The two values are equal, but a holder
 * left on the original keeps the whole tree in memory, however much of it is stored.</p>
 *
 * @param <V> Value type
 */
@FunctionalInterface
public interface Persister<V extends ACell> {

	/**
	 * Persists a non-null value. Persisting a value whose cells are already stored
	 * finds them there and writes nothing new.
	 *
	 * @param value Value to persist
	 * @return Equal value whose cells are retained in the store
	 * @throws IOException If the store fails
	 */
	V persist(V value) throws IOException;
}
