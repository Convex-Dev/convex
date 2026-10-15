package convex.lattice;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import convex.core.data.ACell;
import convex.core.data.Cells;
import convex.core.exceptions.StoreException;
import convex.core.store.AStore;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.Root;
import convex.lattice.cursor.PublishingCursor;

/**
 * Generic store-backed root for a lattice application component hierarchy.
 *
 * <p>A root component owns the host store, the root lattice and the root cursor's
 * sync policy. It is the one generic component with a persistence capability;
 * cursors and domain components have none. Standalone roots publish their current
 * value as the store root on {@link #sync()}. Network infrastructure may configure
 * a replication pipeline before freezing host configuration. Neither operation
 * performs a durability barrier or owns the store lifecycle.</p>
 *
 * <p>The root cursor knows nothing about lattices. Incoming state is delegated to
 * applications. Until configured, this component uses the supplied lattice directly;
 * explicitly configured applications control acceptance and publication. If an
 * application publishes during host persistence, its newer result stays pending
 * without being re-merged against the persisted snapshot.</p>
 *
 * <p>The same component can wrap a cursor owned by a networked NodeServer or
 * own a standalone local root backed by any {@link AStore}.</p>
 *
 * @param <V> Root lattice value type
 */
public final class RootComponent<V extends ACell> extends ALatticeComponent<V> {

	private final ALattice<V> lattice;
	private final AStore store;
	private volatile Function<V,V> publicationPolicy;
	private boolean publicationPolicyFrozen;
	private volatile List<ALatticeApplication<V>> applications;
	private volatile boolean applicationsConfigured;

	/**
	 * Creates a store-backed host around existing atomic storage.
	 *
	 * <p>Use {@link #cursor()} for components whose sync should reach this host.
	 * The supplied storage remains usable for atomic reads and writes, but its
	 * own sync has no publication callback.</p>
	 *
	 * @param lattice Root lattice, the complete merge for the hosted value
	 * @param cursor Root cursor holding the hosted value
	 * @param store Host store
	 */
	public RootComponent(ALattice<V> lattice, Root<V> cursor, AStore store) {
		super(new PublishingCursor<>(cursor));
		this.lattice=Objects.requireNonNull(lattice,"Root lattice must not be null");
		this.store=Objects.requireNonNull(store,"Root store must not be null");
		applications=List.of();
		// Configured applications own acceptance. A racing application publication
		// must not be undone or expanded by the host's different merge strategy.
		cursor().onSync(this::publishRoot,(current,published) -> applicationsConfigured
			?current:lattice.merge(getContext(),current,published));
	}

	/** Creates a standalone local root at the lattice's zero value. */
	public static <V extends ACell> RootComponent<V> create(ALattice<V> lattice, AStore store) {
		return create(lattice,lattice.zero(),store);
	}

	/** Creates a standalone local root with an explicit initial value. */
	public static <V extends ACell> RootComponent<V> create(ALattice<V> lattice, V initialValue, AStore store) {
		return new RootComponent<>(lattice,Cursors.root(initialValue),store);
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

	/** Returns the root lattice hosted here. */
	public ALattice<V> getLattice() {
		return lattice;
	}

	/** Returns the host store without implying ownership of its lifecycle. */
	public AStore store() {
		return store;
	}

	/**
	 * Returns a cursor at a path within the hosted root, with each level's shape
	 * declared from the root lattice so that writes can create absent regions. Hosts
	 * use this to merge path-addressed inbound values with the sub-lattice at the
	 * same path.
	 *
	 * @param <T> Value type at the path
	 * @param path Keys from the root
	 * @return Cursor at the path
	 */
	public <T extends ACell> AForkableCursor<T> resolve(ACell... path) {
		return resolve(cursor,lattice,path);
	}

	/**
	 * Delegates incoming state to the configured applications, without a second
	 * host merge. Each application controls its working state and publication.
	 * Delivery is not atomic across applications: earlier successful merges remain
	 * if a later application fails. Retry is safe for lattice merges.
	 *
	 * <p>Until applications are explicitly configured, the supplied lattice
	 * provides immediate root merging. With private working applications,
	 * application sync must publish their changes before host sync persists them.</p>
	 *
	 * @param other Incoming root value
	 * @return Current published root, which may not yet include the incoming state
	 */
	public V merge(V other) {
		if (!applicationsConfigured) {
			return cursor.updateAndGet(own -> lattice.merge(getContext(),own,other));
		}
		for (ALatticeApplication<V> application:applications) application.merge(other);
		return cursor.get();
	}

	/**
	 * Configures incoming delegation once, before delivering incoming state. The
	 * caller constructs applications against this restored host. An empty list
	 * explicitly ignores incoming values. Configuration retains an immutable list;
	 * applications do not register their children with the root.
	 */
	public synchronized void configureApplications(List<? extends ALatticeApplication<V>> applications) {
		if (applicationsConfigured) throw new IllegalStateException("Applications already configured");
		this.applications=List.copyOf(applications);
		applicationsConfigured=true;
	}

	/**
	 * Persists a value through the host store without installing, publishing or
	 * moving any cursor. The returned value may contain store-backed references.
	 *
	 * @param <T> Cell type
	 * @param value Value to persist
	 * @return Persisted value
	 * @throws IOException If persistence fails
	 */
	public <T extends ACell> T persist(T value) throws IOException {
		return Cells.persist(value,store);
	}

	/**
	 * Persists the current root value without changing the cursor or publishing.
	 *
	 * @return Persisted current root value
	 * @throws IOException If persistence fails
	 */
	public V persist() throws IOException {
		return persist(cursor.get());
	}

	/** Returns the hosted root cursor with its exact static type. */
	@Override
	public PublishingCursor<V> cursor() {
		return (PublishingCursor<V>) cursor;
	}

	/**
	 * Configures the synchronous root publication policy.
	 *
	 * <p>This is a host integration point. Application components call
	 * {@link #sync()} without knowing whether the root is local, replicated or
	 * otherwise hosted. The handler must return the exact published value to install
	 * back into the root cursor, usually with store-backed references.</p>
	 *
	 * <p>Until a policy is configured, sync publishes to this component's store.
	 * Host infrastructure may install a replication pipeline during configuration
	 * and then call {@link #freezePublicationPolicy()} before application work begins.</p>
	 *
	 * @param policy Root publication policy
	 * @throws IllegalStateException If host publication configuration is frozen
	 */
	public synchronized void setPublicationPolicy(Function<V,V> policy) {
		if (publicationPolicyFrozen) {
			throw new IllegalStateException("Root publication policy is frozen");
		}
		if (policy==null) throw new IllegalArgumentException("Root publication policy must not be null");
		publicationPolicy=policy;
	}

	/**
	 * Freezes host publication configuration against later replacement.
	 * Application state may continue to sync normally after this boundary.
	 */
	public synchronized void freezePublicationPolicy() {
		publicationPolicyFrozen=true;
	}

	/**
	 * Flushes the underlying store using its own durability semantics.
	 *
	 * @throws IOException If the store flush fails
	 */
	public void flush() throws IOException {
		store().flush();
	}

	private V publishRoot(V value) {
		Function<V,V> policy=publicationPolicy;
		return (policy==null)?storeRoot(value):policy.apply(value);
	}

	private V storeRoot(V value) {
		try {
			return store().setRootData(value).getValue();
		} catch (IOException e) {
			throw new StoreException("Root component sync failed: persistence error",e);
		}
	}
}
