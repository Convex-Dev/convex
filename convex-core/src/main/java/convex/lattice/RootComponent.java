package convex.lattice;

import java.io.IOException;

import convex.core.data.ACell;
import convex.core.data.Cells;
import convex.core.store.AStore;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.Cursors;

/**
 * Generic store-backed root for a lattice application component hierarchy.
 *
 * <p>A root component supplies store-only persistence to its descendants. It
 * does not change or sync the cursor, select retained store roots, perform a
 * durability barrier or own the store lifecycle. Those remain host policy.</p>
 *
 * <p>The same component can wrap a cursor owned by a networked NodeServer or
 * own a standalone local lattice cursor backed by any {@link AStore}.</p>
 *
 * @param <V> Root lattice value type
 */
public final class RootComponent<V extends ACell> extends ALatticeComponent<V> {

	private final AStore store;

	/** Creates a store-backed root around an existing cursor. */
	public RootComponent(ALatticeCursor<V> cursor, AStore store) {
		super(cursor);
		if (store==null) throw new IllegalArgumentException("Root store must not be null");
		this.store=store;
	}

	/** Creates a standalone local root at the lattice's zero value. */
	public static <V extends ACell> RootComponent<V> create(ALattice<V> lattice, AStore store) {
		return new RootComponent<>(Cursors.createLattice(lattice),store);
	}

	/** Creates a standalone local root with an explicit initial value. */
	public static <V extends ACell> RootComponent<V> create(ALattice<V> lattice, V initialValue, AStore store) {
		return new RootComponent<>(Cursors.createLattice(lattice,initialValue),store);
	}

	/** Returns the host store without implying ownership or root-retention policy. */
	public AStore store() {
		return store;
	}

	/**
	 * Flushes the underlying store using its own durability semantics.
	 *
	 * @throws IOException If the store flush fails
	 */
	public void flush() throws IOException {
		store.flush();
	}

	@Override
	protected <T extends ACell> T persist(T value) throws IOException {
		return Cells.persist(value,store);
	}
}
