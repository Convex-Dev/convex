package convex.core.store;

import java.io.IOException;

import convex.core.data.ACell;

/**
 * Writes a value's cells to a store, returning an equal value whose cells are
 * retained there. A store persists into itself, and {@code LatticeHost::persist}
 * into the host's store. Cursors, components and builders persist through one
 * without knowing the store, whether the value is the one they hold or one they
 * are building.
 *
 * <p>Whoever holds the value must switch to the one returned. On an Etch store
 * persisting builds new cells whose children are soft references, and the cells it
 * was given keep strong references to theirs. The two values are equal, but a holder
 * left on the original keeps the whole tree in memory, however much of it is stored.</p>
 */
public interface Persister {

	/**
	 * Persists a non-null value. Persisting a value whose cells are already stored
	 * finds them there and writes nothing new.
	 *
	 * @param <T> Value type
	 * @param value Value to persist
	 * @return Equal value whose cells are retained in the store
	 * @throws IOException If the store fails
	 */
	<T extends ACell> T persist(T value) throws IOException;
}
