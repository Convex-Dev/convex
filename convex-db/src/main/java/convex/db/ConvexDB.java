package convex.db;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BinaryOperator;
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
 * <p>Component structure:
 * <pre>
 * ConvexDB      db name → database state
 *   └─ SQLDatabase   :tables → table store
 *        └─ SQLSchema     table name → table entry
 *             └─ SQLTable      [schema, rows, ctime, live-count, version?]
 * </pre>
 *
 * <p>How databases merge is the application's policy, supplied when a ConvexDB is
 * constructed, with no default. A database is the unit of merging: the ConvexDB
 * passes its policy to each database through {@link #database(AString)}, and merges
 * the database map by name, each database with that policy. Incoming merges, delivery
 * to a path, publication of forks and replica merges all use it.
 * {@link convex.db.lattice.RowLWW#merge} is the per-row last-write-wins policy that
 * Convex DB provides; an application that serialises its updates supplies its own.
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
 * ConvexDB cdb = ConvexDB.create(RowLWW::merge);
 * SQLDatabase db = cdb.database("mydb");
 * db.tables().createTable("users", new String[]{"id", "name"});
 *
 * // With persistence
 * NodeServer&lt;AHashMap&lt;AString, Index&lt;Keyword, ACell&gt;&gt;&gt; server =
 *     ConvexDB.createNodeServer(store, RowLWW::merge);
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

	private final BinaryOperator<Index<Keyword, ACell>> policy;
	private final Supplier<CVMLong> clock;

	/**
	 * Binds a ConvexDB to the cursor of a database map, stamping writes with the
	 * runtime clock.
	 *
	 * @param cursor Cursor of the database map, which may be absent
	 * @param policy Merge policy for each database, for example {@code RowLWW::merge}
	 */
	public ConvexDB(AForkableCursor<AHashMap<AString, Index<Keyword, ACell>>> cursor,
			BinaryOperator<Index<Keyword, ACell>> policy) {
		this(cursor, policy, Utils::getCurrentTimestampCell);
	}

	/**
	 * Binds a ConvexDB to the cursor of a database map, wherever that lies.
	 * Construction reads and writes nothing. Register the instance with a host as an
	 * application for it to receive incoming values.
	 *
	 * @param cursor Cursor of the database map, which may be absent
	 * @param policy Merge policy for each database, for example {@code RowLWW::merge}
	 * @param clock Clock that stamps writes to every database and table below
	 */
	public ConvexDB(AForkableCursor<AHashMap<AString, Index<Keyword, ACell>>> cursor,
			BinaryOperator<Index<Keyword, ACell>> policy, Supplier<CVMLong> clock) {
		super(cursor);
		this.policy = Objects.requireNonNull(policy, "Merge policy must not be null");
		this.clock = Objects.requireNonNull(clock, "Clock must not be null");
	}

	/**
	 * Creates a new standalone ConvexDB (in-memory, no persistence), stamping writes
	 * with the runtime clock.
	 *
	 * @param policy Merge policy for each database, for example {@code RowLWW::merge}
	 * @return New ConvexDB instance
	 */
	public static ConvexDB create(BinaryOperator<Index<Keyword, ACell>> policy) {
		return create(policy, Utils::getCurrentTimestampCell);
	}

	/**
	 * Creates a new standalone ConvexDB (in-memory, no persistence).
	 *
	 * @param policy Merge policy for each database, for example {@code RowLWW::merge}
	 * @param clock Clock that stamps writes
	 * @return New ConvexDB instance
	 */
	public static ConvexDB create(BinaryOperator<Index<Keyword, ACell>> policy, Supplier<CVMLong> clock) {
		return new ConvexDB(Cursors.root(Maps.empty()), policy, clock);
	}

	/**
	 * Connects directly to an existing cursor chain at the database-map level,
	 * without registering for incoming values, stamping writes with the runtime clock.
	 *
	 * @param parent Parent lattice cursor at the database-map level
	 * @param policy Merge policy for each database, for example {@code RowLWW::merge}
	 * @return ConvexDB connected to the cursor chain
	 */
	public static ConvexDB connect(AForkableCursor<?> parent, BinaryOperator<Index<Keyword, ACell>> policy) {
		return new ConvexDB(parent.path(), policy);
	}

	/**
	 * Returns the ConvexDB registered with a host assembled by {@link #openHost} or
	 * {@link #createNodeServer}, with its policy and clock. For any other containing
	 * component, use {@link #connect(ALatticeComponent, BinaryOperator)} and supply the
	 * policy.
	 *
	 * @param parent Host assembled by {@link #openHost}
	 * @return The registered ConvexDB
	 * @throws IllegalArgumentException if no ConvexDB is registered with the component
	 */
	public static ConvexDB connect(ALatticeComponent<AHashMap<AString, Index<Keyword, ACell>>> parent) {
		if (parent==null) throw new IllegalArgumentException("Parent component must not be null");
		ConvexDB registered = (parent instanceof LatticeHost<?> host) ? hosted.get(host) : null;
		if (registered == null) throw new IllegalArgumentException("No ConvexDB is registered with this component; supply a merge policy");
		return registered;
	}

	/**
	 * Connects to a containing component at the database-map level, as a live view of
	 * the component's cursor that receives no incoming values and stamps writes with the
	 * runtime clock.
	 *
	 * @param parent Containing database-map component
	 * @param policy Merge policy for each database, for example {@code RowLWW::merge}
	 * @return ConvexDB over the containing component's cursor
	 */
	public static ConvexDB connect(ALatticeComponent<AHashMap<AString, Index<Keyword, ACell>>> parent,
			BinaryOperator<Index<Keyword, ACell>> policy) {
		if (parent==null) throw new IllegalArgumentException("Parent component must not be null");
		return new ConvexDB(parent.cursor(), policy);
	}

	/**
	 * Creates optional networking around a store-backed ConvexDB host.
	 * Call {@code server.getHost().checkpoint()} to save durably while running. At
	 * shutdown, call {@code server.stopIngress()}, close {@code server.getHost()} to
	 * save, then close the server, which sends that final checkpoint to peers, then
	 * the store; network close does not save pending state. Local-only applications
	 * can use {@link #openHost}.
	 *
	 * <p>Usage:
	 * <pre>
	 * EtchStore store = EtchStore.createTemp();
	 * NodeServer&lt;AHashMap&lt;AString, Index&lt;Keyword, ACell&gt;&gt;&gt; server =
	 *     ConvexDB.createNodeServer(store, RowLWW::merge);
	 * server.launch();
	 * ConvexDB cdb = ConvexDB.connect(server.getHost());
	 * </pre>
	 *
	 * @param store Store for persistence (e.g. {@code EtchStore.createTemp()})
	 * @param policy Merge policy for each database, for example {@code RowLWW::merge}
	 * @return NodeServer over a host assembled by {@link #openHost}
	 * @throws IOException if the store's retained root cannot be read
	 */
	public static NodeServer<AHashMap<AString, Index<Keyword, ACell>>> createNodeServer(AStore store,
			BinaryOperator<Index<Keyword, ACell>> policy) throws IOException {
		return createNodeServer(store, policy, Utils::getCurrentTimestampCell);
	}

	/**
	 * Creates optional networking around a store-backed ConvexDB host whose writes are
	 * stamped with the given clock, as {@link #createNodeServer(AStore, BinaryOperator)}.
	 *
	 * @param store Store for persistence (e.g. {@code EtchStore.createTemp()})
	 * @param policy Merge policy for each database, for example {@code RowLWW::merge}
	 * @param clock Clock that stamps writes
	 * @return NodeServer over a host assembled by {@link #openHost(AStore, BinaryOperator, Supplier)}
	 * @throws IOException if the store's retained root cannot be read
	 */
	public static NodeServer<AHashMap<AString, Index<Keyword, ACell>>> createNodeServer(AStore store,
			BinaryOperator<Index<Keyword, ACell>> policy, Supplier<CVMLong> clock) throws IOException {
		return new NodeServer<>(openHost(store, policy, clock));
	}

	/**
	 * Opens a host whose root is a database map, as
	 * {@link #openHost(AStore, BinaryOperator, Supplier)}, stamping writes with the
	 * runtime clock.
	 *
	 * @param store Store for persistence, or null for an in-memory host
	 * @param policy Merge policy for each database, for example {@code RowLWW::merge}
	 * @return Host with a registered ConvexDB
	 * @throws IOException if the store's retained root cannot be read
	 */
	public static LatticeHost<AHashMap<AString,Index<Keyword,ACell>>> openHost(AStore store,
			BinaryOperator<Index<Keyword, ACell>> policy) throws IOException {
		return openHost(store, policy, Utils::getCurrentTimestampCell);
	}

	/**
	 * Opens a host whose root is a database map, restoring the root the store retains,
	 * and registers a live ConvexDB with it as its application, so incoming values are
	 * merged with the given policy. {@link #connect(ALatticeComponent)} returns that
	 * instance. This is a convenience: an application that roots its databases
	 * elsewhere assembles its own host.
	 *
	 * @param store Store for persistence, or null for an in-memory host
	 * @param policy Merge policy for each database, for example {@code RowLWW::merge}
	 * @param clock Clock that stamps writes
	 * @return Host with a registered ConvexDB
	 * @throws IOException if the store's retained root cannot be read
	 */
	public static LatticeHost<AHashMap<AString,Index<Keyword,ACell>>> openHost(AStore store,
			BinaryOperator<Index<Keyword, ACell>> policy, Supplier<CVMLong> clock) throws IOException {
		LatticeHost<AHashMap<AString,Index<Keyword,ACell>>> host = LatticeHost.open(Maps.empty(), store);
		hosted.put(host, host.addApplication(new ConvexDB(host.cursor(), policy, clock)));
		return host;
	}

	/** Subclasses override this to return their own type, so forks keep their databases, policy and clock. */
	@Override
	protected ConvexDB rebind(AForkableCursor<AHashMap<AString, Index<Keyword, ACell>>> cursor) {
		return new ConvexDB(cursor, policy, clock);
	}

	/** Returns the merge policy passed to each database. */
	public BinaryOperator<Index<Keyword, ACell>> getPolicy() {
		return policy;
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
	 * Returns the component for a named database, which may be absent, with this
	 * ConvexDB's policy and clock. Connecting and reading create nothing; the first
	 * changed write creates the database. Merge, delivery and database operations all
	 * use this component, so an override supplying a database with another policy
	 * changes how that database merges.
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
	 * This database map's merge: a union of database names, with each database's state
	 * merged by the policy of its {@link #database(AString)} component. Databases merge
	 * independently, since nothing spans them. It is a pure function that reads and
	 * writes no state. Entries that are not database states are ignored, and an absent
	 * map stays absent when nothing is accepted.
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
	 * Gets a JDBC Connection bound to a named database within this ConvexDB.
	 * The database is reached through the connection only: neither the registry
	 * nor the driver's URL instances see it, and the name may be any string.
	 *
	 * <p>This is the preferred API when you already hold a ConvexDB instance
	 * (e.g. from {@link #connect(ALatticeComponent)}).
	 *
	 * @param dbName Database name within this ConvexDB
	 * @return JDBC Connection backed by this instance's cursor chain
	 * @throws SQLException if connection creation fails
	 */
	public Connection getConnection(String dbName) throws SQLException {
		return ConvexDriver.connect(this, dbName);
	}
}
