package convex.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.store.AStore;
import convex.db.jdbc.ConvexDriver;
import convex.db.lattice.SQLDatabase;
import convex.db.lattice.TableStoreLattice;
import convex.lattice.ALatticeComponent;
import convex.lattice.RootComponent;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.MapLattice;
import convex.node.NodeServer;

/**
 * Root lattice component for the Convex DB SQL database system.
 *
 * <p>A ConvexDB instance wraps a lattice cursor at the database-map level,
 * managing multiple named {@link SQLDatabase} instances. It is the entry point
 * for creating, connecting, and registering databases for JDBC and PostgreSQL
 * wire protocol access.
 *
 * <p>Lattice structure:
 * <pre>
 * ConvexDB (MapLattice: db-name → database-state)
 *   └─ SQLDatabase (KeyedLattice: :tables → TableStoreLattice)
 *        └─ SQLSchema / SQLTable / SQLRow
 * </pre>
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
 * ConvexDB cdb = ConvexDB.connect(server.getRootComponent());
 * cdb.register();
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

	// ========== Lattice structure ==========

	/** Keyword for the tables section within a database. */
	public static final Keyword KEY_TABLES = Keyword.intern("tables");

	/**
	 * The lattice type for a single database.
	 * Structure: KeyedLattice { :tables → TableStoreLattice }
	 */
	public static final KeyedLattice DATABASE_LATTICE =
		KeyedLattice.create(KEY_TABLES, TableStoreLattice.INSTANCE);

	/**
	 * The lattice type for the database map (db name → database state).
	 */
	public static final MapLattice<AString, Index<Keyword, ACell>>
		DATABASE_MAP_LATTICE = MapLattice.create(DATABASE_LATTICE);

	// ========== Named instance registry ==========

	/** Registry of ConvexDB instances available for JDBC/PG access. */
	private static final ConcurrentHashMap<String, ConvexDB> registry = new ConcurrentHashMap<>();

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

	private ConvexDB(ALatticeCursor<AHashMap<AString, Index<Keyword, ACell>>> cursor) {
		this(null,cursor);
	}

	private ConvexDB(
			ALatticeComponent<AHashMap<AString, Index<Keyword, ACell>>> parent,
			ALatticeCursor<AHashMap<AString, Index<Keyword, ACell>>> cursor) {
		super(parent,cursor);
	}

	/**
	 * Creates a new standalone ConvexDB (in-memory, no persistence).
	 *
	 * @return New ConvexDB instance
	 */
	public static ConvexDB create() {
		ALatticeCursor<AHashMap<AString, Index<Keyword, ACell>>> cursor =
			Cursors.createLattice(DATABASE_MAP_LATTICE);
		return new ConvexDB(cursor);
	}

	/**
	 * Connects directly to an existing cursor chain without a containing
	 * component policy. This form is retained for standalone and compatibility
	 * use; hosted applications should use {@link #connect(ALatticeComponent)}
	 * with {@link NodeServer#getRootComponent()}.
	 *
	 * @param parent Parent lattice cursor at the database-map level
	 * @return ConvexDB connected to the cursor chain
	 */
	public static ConvexDB connect(ALatticeCursor<?> parent) {
		return new ConvexDB(parent.path());
	}

	/**
	 * Connects to a containing component at the database-map level.
	 *
	 * <p>This is the preferred hosted form. Component policy such as persistence
	 * delegates through the supplied parent, while cursor writes remain at the
	 * same database-map position. A {@link RootComponent} obtained from a
	 * {@link NodeServer} can be supplied directly.</p>
	 *
	 * @param parent Containing database-map component
	 * @return ConvexDB connected to the component hierarchy
	 */
	public static ConvexDB connect(
			ALatticeComponent<AHashMap<AString, Index<Keyword, ACell>>> parent) {
		if (parent==null) throw new IllegalArgumentException("Parent component must not be null");
		return new ConvexDB(parent,parent.cursor());
	}

	/**
	 * Creates a persisted ConvexDB backed by a local-only {@link NodeServer}.
	 * The NodeServer handles Etch persistence; call {@code server.close()} to
	 * flush and shut down.
	 *
	 * <p>Usage:
	 * <pre>
	 * EtchStore store = EtchStore.createTemp();
	 * NodeServer&lt;AHashMap&lt;AString, Index&lt;Keyword, ACell&gt;&gt;&gt; server =
	 *     ConvexDB.createNodeServer(store);
	 * server.launch();
	 * ConvexDB cdb = ConvexDB.connect(server.getRootComponent());
	 * </pre>
	 *
	 * @param store Store for persistence (e.g. {@code EtchStore.createTemp()})
	 * @return NodeServer configured for SQL database lattice (local-only, no network)
	 */
	public static NodeServer<AHashMap<AString, Index<Keyword, ACell>>> createNodeServer(AStore store) {
		return new NodeServer<>(DATABASE_MAP_LATTICE,store);
	}

	/**
	 * Gets or creates a named database within this ConvexDB.
	 * Navigates the cursor tree to the named database path; initialises
	 * it with a zero value if it doesn't exist yet.
	 *
	 * @param name Database name
	 * @return SQLDatabase connected to the cursor chain
	 */
	public SQLDatabase database(String name) {
		return SQLDatabase.connect(this,name);
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
		AHashMap<AString, Index<Keyword, ACell>> map = cursor.get();
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
	 * (e.g. from {@link #connect(ALatticeCursor)}).
	 *
	 * @param dbName Database name within this ConvexDB
	 * @return JDBC Connection backed by this instance's cursor chain
	 * @throws SQLException if connection creation fails
	 */
	public Connection getConnection(String dbName) throws SQLException {
		return ConvexDriver.connect(this, dbName);
	}
}
