package convex.db;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.store.AStore;
import convex.core.util.MergeFunction;
import convex.core.util.Utils;
import convex.db.jdbc.ConvexDriver;
import convex.db.lattice.SQLDatabase;
import convex.lattice.ALatticeComponent;
import convex.lattice.LatticeHost;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Cursors;
import convex.node.NodeServer;

/**
 * Root lattice component for the Convex DB SQL database system.
 *
 * <p>A ConvexDB instance wraps a lattice cursor at the database-map level,
 * managing multiple named {@link SQLDatabase} instances. It is the entry point
 * for creating, connecting, and registering databases for JDBC and PostgreSQL
 * wire protocol access.
 *
 * <p>Component structure, each level merging with its own rule:
 * <pre>
 * ConvexDB      db name → database state
 *   └─ SQLDatabase   :tables → table store
 *        └─ SQLSchema     table name → table entry
 *             └─ SQLTable      [schema, rows, utime, live-count, version?]
 * </pre>
 *
 * <p>Merges are executed by these components: each level's {@code mergeValues} is a
 * pure rule that calls the rule of the child component for every key, down to the
 * table, which merges its rows. Incoming merges, delivery to a path, publication of
 * forks and replica merges all use the same rules. An application changes them by
 * extending a component and supplying it from the factory above it:
 * {@link #database(AString)}, {@link SQLDatabase#tables()} and
 * {@code SQLSchema.table(AString)}. Subclasses also override {@code rebind} so that
 * forks keep their type.
 *
 * <p>A ConvexDB takes the cursor for the database map and assumes nothing about where
 * that lies: an application can root it anywhere in its own state, and registers it
 * with its host to receive incoming values. {@link #openHost} is a convenience that
 * assembles a host whose root is the database map.
 *
 * <p>The application also supplies the clock that stamps writes, defaulting to the
 * runtime clock. Factories pass it down to every database and table, forks keep it,
 * and each write reads it once; merges never read it. How it orders a writer's writes,
 * for example with strictly increasing stamps, is the application's policy.
 *
 * <p>Usage:
 * <pre>
 * // Standalone (in-memory)
 * ConvexDB cdb = ConvexDB.create();
 * SQLDatabase db = cdb.database("mydb");
 * db.tables().createTable("users", new String[]{"id", "name"});
 *
 * // With persistence
 * NodeServer&lt;AHashMap&lt;AString, Index&lt;Keyword, ACell&gt;&gt;&gt; server =
 *     ConvexDB.createNodeServer(store);
 * server.launch();
 * ConvexDB cdb = ConvexDB.connect(server.getHost());
 * cdb.register("mydb");
 *
 * // JDBC access (after register)
 * Connection conn = DriverManager.getConnection("jdbc:convex:database=mydb");
 * </pre>
 */
public class ConvexDB extends ALatticeComponent<AHashMap<AString, Index<Keyword, ACell>>> {

	/**
	 * Version string for the Convex DB module.
	 */
	public static final String VERSION = "0.8.4-SNAPSHOT";

	/** Keyword for the tables section within a database. */
	public static final Keyword KEY_TABLES = Keyword.intern("tables");

	// ========== Named instance registry ==========

	/** Registry of ConvexDB instances available for JDBC/PG access. */
	private static final ConcurrentHashMap<String, ConvexDB> registry = new ConcurrentHashMap<>();

	/** The application each host assembled by {@link #openHost} has registered. */
	private static final Map<LatticeHost<?>, ConvexDB> hosted = Collections.synchronizedMap(new WeakHashMap<>());

	/**
	 * Looks up a registered ConvexDB by database name.
	 *
	 * @param dbName The database name
	 * @return The ConvexDB instance that contains the named database, or null
	 */
	public static ConvexDB lookup(String dbName) {
		return registry.get(dbName);
	}

	/**
	 * Looks up a registered database by name.
	 *
	 * @param dbName The database name
	 * @return The SQLDatabase, or null if not registered
	 */
	public static SQLDatabase lookupDatabase(String dbName) {
		ConvexDB cdb = registry.get(dbName);
		return (cdb != null) ? cdb.database(dbName) : null;
	}

	// ========== Instance ==========

	private final Supplier<CVMLong> clock;

	/**
	 * Binds a ConvexDB to the cursor of a database map, stamping writes with the
	 * runtime clock.
	 *
	 * @param cursor Cursor of the database map, which may be absent
	 */
	public ConvexDB(AForkableCursor<AHashMap<AString, Index<Keyword, ACell>>> cursor) {
		this(cursor, Utils::getCurrentTimestampCell);
	}

	/**
	 * Binds a ConvexDB to the cursor of a database map, wherever that lies.
	 * Construction reads and writes nothing. Register the instance with a host as an
	 * application for it to receive incoming values.
	 *
	 * @param cursor Cursor of the database map, which may be absent
	 * @param clock Clock that stamps writes to every database and table below
	 */
	public ConvexDB(AForkableCursor<AHashMap<AString, Index<Keyword, ACell>>> cursor, Supplier<CVMLong> clock) {
		super(cursor);
		this.clock = Objects.requireNonNull(clock, "Clock must not be null");
	}

	/**
	 * Creates a new standalone ConvexDB (in-memory, no persistence), stamping writes
	 * with the runtime clock.
	 *
	 * @return New ConvexDB instance
	 */
	public static ConvexDB create() {
		return create(Utils::getCurrentTimestampCell);
	}

	/**
	 * Creates a new standalone ConvexDB (in-memory, no persistence).
	 *
	 * @param clock Clock that stamps writes
	 * @return New ConvexDB instance
	 */
	public static ConvexDB create(Supplier<CVMLong> clock) {
		return new ConvexDB(Cursors.root(Maps.empty()), clock);
	}

	/**
	 * Connects directly to an existing cursor chain at the database-map level,
	 * without registering for incoming values, stamping writes with the runtime clock.
	 *
	 * @param parent Parent lattice cursor at the database-map level
	 * @return ConvexDB connected to the cursor chain
	 */
	public static ConvexDB connect(AForkableCursor<?> parent) {
		return new ConvexDB(parent.path());
	}

	/**
	 * Connects to a containing component at the database-map level. For a host
	 * assembled by {@link #openHost} or {@link #createNodeServer}, this returns the
	 * ConvexDB registered with it, with its clock; otherwise it returns a live view of
	 * the component's cursor, which receives no incoming values and stamps writes with
	 * the runtime clock.
	 *
	 * @param parent Containing database-map component, such as a host
	 * @return ConvexDB over the containing component's cursor
	 */
	public static ConvexDB connect(
			ALatticeComponent<AHashMap<AString, Index<Keyword, ACell>>> parent) {
		if (parent==null) throw new IllegalArgumentException("Parent component must not be null");
		ConvexDB registered = (parent instanceof LatticeHost<?> host) ? hosted.get(host) : null;
		return (registered != null) ? registered : new ConvexDB(parent.cursor());
	}

	/**
	 * Creates optional networking around a store-backed ConvexDB host.
	 * Call {@code server.getHost().checkpoint()} to save durably while running. At
	 * shutdown, close the server, then {@code server.getHost()} to save, then the
	 * store; network close does not save pending state. Local-only applications can
	 * use {@link #openHost}.
	 *
	 * <p>Usage:
	 * <pre>
	 * EtchStore store = EtchStore.createTemp();
	 * NodeServer&lt;AHashMap&lt;AString, Index&lt;Keyword, ACell&gt;&gt;&gt; server =
	 *     ConvexDB.createNodeServer(store);
	 * server.launch();
	 * ConvexDB cdb = ConvexDB.connect(server.getHost());
	 * </pre>
	 *
	 * @param store Store for persistence (e.g. {@code EtchStore.createTemp()})
	 * @return NodeServer over a host assembled by {@link #openHost}
	 * @throws IOException if the store's retained root cannot be read
	 */
	public static NodeServer<AHashMap<AString, Index<Keyword, ACell>>> createNodeServer(AStore store) throws IOException {
		return createNodeServer(store, Utils::getCurrentTimestampCell);
	}

	/**
	 * Creates optional networking around a store-backed ConvexDB host whose writes are
	 * stamped with the given clock, as {@link #createNodeServer(AStore)}.
	 *
	 * @param store Store for persistence (e.g. {@code EtchStore.createTemp()})
	 * @param clock Clock that stamps writes
	 * @return NodeServer over a host assembled by {@link #openHost(AStore, Supplier)}
	 * @throws IOException if the store's retained root cannot be read
	 */
	public static NodeServer<AHashMap<AString, Index<Keyword, ACell>>> createNodeServer(AStore store,
			Supplier<CVMLong> clock) throws IOException {
		return new NodeServer<>(openHost(store, clock));
	}

	/**
	 * Opens a host whose root is a database map, as {@link #openHost(AStore, Supplier)},
	 * stamping writes with the runtime clock.
	 *
	 * @param store Store for persistence, or null for an in-memory host
	 * @return Host with a registered ConvexDB
	 * @throws IOException if the store's retained root cannot be read
	 */
	public static LatticeHost<AHashMap<AString,Index<Keyword,ACell>>> openHost(AStore store) throws IOException {
		return openHost(store, Utils::getCurrentTimestampCell);
	}

	/**
	 * Opens a host whose root is a database map, restoring the root the store retains,
	 * and registers a live ConvexDB with it as its application, so incoming values are
	 * merged by the Convex DB components. {@link #connect(ALatticeComponent)} returns
	 * that instance. This is a convenience: an application that roots its databases
	 * elsewhere, or extends the components, assembles its own host.
	 *
	 * @param store Store for persistence, or null for an in-memory host
	 * @param clock Clock that stamps writes
	 * @return Host with a registered ConvexDB
	 * @throws IOException if the store's retained root cannot be read
	 */
	public static LatticeHost<AHashMap<AString,Index<Keyword,ACell>>> openHost(AStore store,
			Supplier<CVMLong> clock) throws IOException {
		LatticeHost<AHashMap<AString,Index<Keyword,ACell>>> host = LatticeHost.open(Maps.empty(), store);
		hosted.put(host, host.addApplication(new ConvexDB(host.cursor(), clock)));
		return host;
	}

	/** Subclasses override this to return their own type, so forks keep their databases and clock. */
	@Override
	protected ConvexDB rebind(AForkableCursor<AHashMap<AString, Index<Keyword, ACell>>> cursor) {
		return new ConvexDB(cursor, clock);
	}

	/** Returns the clock that stamps writes to every database and table below. */
	public Supplier<CVMLong> getClock() {
		return clock;
	}

	@Override
	public ConvexDB fork() {
		return rebind(storage.fork());
	}

	/**
	 * Returns the cursor of a named database's state, for constructing database
	 * components. A missing database map is created only by a changed write.
	 *
	 * @param name Database name
	 * @return Cursor of the database's state, which may be absent
	 */
	public AForkableCursor<Index<Keyword, ACell>> path(AString name) {
		return cursor().withDefault(Maps.empty()).path(name);
	}

	/**
	 * Returns the component for a named database, which may be absent. Connecting
	 * and reading create nothing; the first changed write creates the database.
	 * Merge, delivery and database operations all use this component, so an override
	 * supplying another {@link SQLDatabase} changes how that database merges.
	 *
	 * @param name Database name
	 * @return SQLDatabase bound to {@link #path(AString)}
	 */
	public SQLDatabase database(AString name) {
		return SQLDatabase.connect(this, name);
	}

	/**
	 * Returns the component for a named database, as {@link #database(AString)}.
	 *
	 * @param name Database name
	 * @return SQLDatabase connected to the cursor chain
	 */
	public SQLDatabase database(String name) {
		return database(Strings.create(name));
	}

	/**
	 * This database map's merge rule: a union of database names, with each database's
	 * state merged by the rule of its {@link #database(AString)} component. It is a pure
	 * function that reads and writes no state. Entries that are not database states are
	 * ignored, and an absent map stays absent when nothing is accepted.
	 *
	 * @param own Established database map, kept on ties; null if absent
	 * @param other Database map to merge in; null if absent
	 * @return Merged database map
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public AHashMap<AString, Index<Keyword, ACell>> mergeValues(AHashMap<AString, Index<Keyword, ACell>> own,
			AHashMap<AString, Index<Keyword, ACell>> other) {
		ACell incoming = other;
		if (!(incoming instanceof AHashMap)) return own;
		// Foreign entries stay untyped until they are checked as database states
		AHashMap<AString, ACell> base = (own == null) ? Maps.empty() : (AHashMap) own;
		AHashMap<AString, ACell> merged = base.mergeDifferences((AHashMap<AString, ACell>) incoming, new MergeFunction<ACell>() {
			@Override
			public ACell merge(ACell a, ACell b) {
				throw new IllegalStateException("Database merge requires the database name");
			}

			@Override
			public ACell merge(Object key, ACell a, ACell b) {
				if (!(key instanceof AString name) || !(b instanceof Index)) return a;
				if (a != null && !(a instanceof Index)) return a;
				return database(name).mergeValues((Index<Keyword, ACell>) a, (Index<Keyword, ACell>) b);
			}
		});
		return (own == null && merged.isEmpty()) ? null : (AHashMap) merged;
	}

	@Override
	public AHashMap<AString, Index<Keyword, ACell>> merge(AHashMap<AString, Index<Keyword, ACell>> incoming) {
		return cursor().updateAndGet(own -> mergeValues(own, incoming));
	}

	@Override
	protected AHashMap<AString, Index<Keyword, ACell>> mergePublication(AHashMap<AString, Index<Keyword, ACell>> own,
			AHashMap<AString, Index<Keyword, ACell>> other) {
		return mergeValues(own, other);
	}

	/** Each database name resolves to its {@link #database(AString)} component. */
	@SuppressWarnings("unchecked")
	@Override
	protected <T extends ACell> ALatticeComponent<T> child(ACell key) {
		return (key instanceof AString name) ? (ALatticeComponent<T>) (ALatticeComponent<?>) database(name) : null;
	}

	/**
	 * Registers a named database for JDBC and PostgreSQL wire protocol access.
	 * After calling this, the database can be connected via
	 * {@code jdbc:convex:database=<name>}.
	 *
	 * @param dbName The database name to register
	 * @return This ConvexDB instance, for chaining
	 */
	public ConvexDB register(String dbName) {
		registry.put(dbName, this);
		return this;
	}

	/**
	 * Returns the names of all databases in this ConvexDB that have state.
	 *
	 * @return Array of database names
	 */
	public String[] getDatabaseNames() {
		AHashMap<AString, Index<Keyword, ACell>> map = cursor().get();
		if (map == null) return new String[0];
		java.util.List<String> names = new java.util.ArrayList<>();
		for (var entry : map.entrySet()) {
			names.add(entry.getKey().toString());
		}
		return names.toArray(new String[0]);
	}

	/**
	 * Unregisters a database name from JDBC/PG access.
	 *
	 * @param dbName The database name to unregister
	 */
	public void unregister(String dbName) {
		registry.remove(dbName);
	}

	/**
	 * Gets a JDBC Connection for a named database within this ConvexDB.
	 * Bypasses DriverManager and the static registry entirely.
	 *
	 * <p>This is the preferred API when you already hold a ConvexDB instance
	 * (e.g. from {@link #connect(AForkableCursor)}).
	 *
	 * @param dbName Database name within this ConvexDB
	 * @return JDBC Connection backed by this instance's cursor chain
	 * @throws SQLException if connection creation fails
	 */
	public Connection getConnection(String dbName) throws SQLException {
		return ConvexDriver.connect(this, dbName);
	}
}
