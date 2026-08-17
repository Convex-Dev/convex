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
	private final boolean persistenceEnabled;

	/** Creates a store-backed root around an existing cursor. */
	public RootComponent(ALatticeCursor<V> cursor, AStore store) {
		this(cursor,store,true);
	}

	/**
	 * Creates a root around an existing cursor with explicit persistence policy.
	 *
	 * @param cursor Root lattice cursor
	 * @param store Store available to the application host
	 * @param persistenceEnabled true to persist delegated values
	 */
	public RootComponent(ALatticeCursor<V> cursor, AStore store, boolean persistenceEnabled) {
		super(cursor);
		if (store==null) throw new IllegalArgumentException("Root store must not be null");
		this.store=store;
		this.persistenceEnabled=persistenceEnabled;
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

	/** Returns whether delegated store persistence is enabled. */
	public boolean isPersistenceEnabled() {
		return persistenceEnabled;
	}

	@Override
	protected <T extends ACell> T persist(T value) throws IOException {
		if (!persistenceEnabled) return value;
		return Cells.persist(value,store);
	}
}
