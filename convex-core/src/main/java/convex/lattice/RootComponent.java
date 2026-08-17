package convex.lattice;

import java.io.IOException;
import java.util.function.Function;

import convex.core.data.ACell;
import convex.core.data.Cells;
import convex.core.exceptions.StoreException;
import convex.core.store.AStore;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.RootLatticeCursor;

/**
 * Generic store-backed root for a lattice application component hierarchy.
 *
 * <p>A root component supplies store-only persistence to its descendants and
 * hosts the root cursor's sync policy. Standalone roots publish their current
 * value as the store root on {@link #sync()}. A network host may replace that
 * handler with a replication pipeline. Neither operation performs a durability
 * barrier or owns the store lifecycle.</p>
 *
 * <p>The same component can wrap a cursor owned by a networked NodeServer or
 * own a standalone local lattice cursor backed by any {@link AStore}.</p>
 *
 * @param <V> Root lattice value type
 */
public final class RootComponent<V extends ACell> extends ALatticeComponent<V> {

	private final AStore store;
	private final RootLatticeCursor<V> rootCursor;

	/** Creates a store-backed host around an existing root cursor. */
	public RootComponent(RootLatticeCursor<V> cursor, AStore store) {
		super(cursor);
		if (store==null) throw new IllegalArgumentException("Root store must not be null");
		this.store=store;
		this.rootCursor=cursor;
		rootCursor.onSync(this::storeRoot);
	}

	/** Creates a standalone local root at the lattice's zero value. */
	public static <V extends ACell> RootComponent<V> create(ALattice<V> lattice, AStore store) {
		return new RootComponent<>(Cursors.createLattice(lattice),store);
	}

	/** Creates a standalone local root with an explicit initial value. */
	public static <V extends ACell> RootComponent<V> create(ALattice<V> lattice, V initialValue, AStore store) {
		return new RootComponent<>(Cursors.createLattice(lattice,initialValue),store);
	}

	/**
	 * Opens a standalone local root from the store's retained root value, or the
	 * lattice's zero value when the store has no retained root.
	 *
	 * <p>The caller statically supplies the lattice corresponding to the store.
	 * Store values are deliberately not subject to redundant runtime type checks.</p>
	 *
	 * @param <V> Root lattice value type
	 * @param lattice Root lattice definition
	 * @param store Store holding the retained root
	 * @return Store-backed root component
	 * @throws IOException If the retained root cannot be read
	 */
	public static <V extends ACell> RootComponent<V> open(ALattice<V> lattice, AStore store) throws IOException {
		if (store==null) throw new IllegalArgumentException("Root store must not be null");
		V value=store.getRootData();
		return create(lattice,(value==null)?lattice.zero():value,store);
	}

	/** Returns the host store without implying ownership of its lifecycle. */
	public AStore store() {
		return store;
	}

	/** Returns the hosted root cursor with its exact static type. */
	@Override
	public RootLatticeCursor<V> cursor() {
		return rootCursor;
	}

	/**
	 * Replaces the synchronous root publication handler.
	 *
	 * <p>This is a host integration point. Application components call
	 * {@link #sync()} without knowing whether the root is local, replicated or
	 * otherwise hosted. The handler must return the exact published value to install
	 * back into the root cursor, usually with store-backed references.</p>
	 *
	 * @param handler Root publication handler, or null to disable publication
	 */
	public void onSync(Function<V,V> handler) {
		rootCursor.onSync(handler);
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

	private V storeRoot(V value) {
		try {
			return store.setRootData(value).getValue();
		} catch (IOException e) {
			throw new StoreException("Root component sync failed: persistence error",e);
		}
	}
}
