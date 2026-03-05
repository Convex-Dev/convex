package convex.db.lattice;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.store.AStore;
import convex.lattice.ALatticeComponent;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.MapLattice;
import convex.lattice.generic.OwnerLattice;
import convex.node.NodeConfig;
import convex.node.NodeServer;

/**
 * A named SQL database within the global lattice, with per-owner signed replicas.
 *
 * <p>Sits at the lattice path {@code :sql / <owner-key>} and handles:
 * <ul>
 *   <li>Signing this node's database with its key pair</li>
 *   <li>Merging remote replicas into the local store (absorption merge)</li>
 *   <li>Rejecting replicas with invalid signatures via {@link OwnerLattice}</li>
 * </ul>
 *
 * <p>Lattice path structure:
 * <pre>
 * :sql → OwnerLattice → SignedLattice → MapLattice → TableStoreLattice
 *         owner-key → signed(db-name → table-store)
 * </pre>
 *
 * <p>Two usage modes:
 * <ul>
 *   <li><b>Standalone</b> ({@link #create}): owns its own cursor, manual export/merge</li>
 *   <li><b>Connected</b> ({@link #connect}): connected to a parent cursor chain,
 *       signing and replication handled by the chain</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 * // Standalone
 * SQLDatabase db = SQLDatabase.create("mydb", keyPair);
 * db.tables().createTable("users", new String[]{"id", "name", "email"});
 * db.mergeReplicas(remoteOwnerMap);
 *
 * // Connected (e.g. from a SignedCursor in a NodeServer)
 * SQLDatabase db = SQLDatabase.connect(signedCursor, "mydb");
 * db.tables().insert("users", key, values);
 * </pre>
 */
public class SQLDatabase extends ALatticeComponent<Index<Keyword, ACell>> {

	/** Keyword for the tables section within a database. */
	public static final Keyword KEY_TABLES = Keyword.intern("tables");

	/**
	 * The lattice type for a single database.
	 * Structure: KeyedLattice { :tables → TableStoreLattice }
	 */
	public static final KeyedLattice DATABASE_LATTICE =
		KeyedLattice.create(KEY_TABLES, TableStoreLattice.INSTANCE);

	/**
	 * The lattice type for a database map (db name → database state).
	 * Use this when creating a {@link NodeServer} for SQL databases.
	 */
	public static final MapLattice<AString, Index<Keyword, ACell>>
		DATABASE_MAP_LATTICE = MapLattice.create(DATABASE_LATTICE);

	/**
	 * The OwnerLattice structure for SQL databases in the global lattice.
	 * Structure: OwnerLattice → SignedLattice → MapLattice → DatabaseLattice
	 */
	public static final OwnerLattice<AHashMap<AString, Index<Keyword, ACell>>>
		OWNER_LATTICE = OwnerLattice.create(DATABASE_MAP_LATTICE);

	private final AString dbName;
	private final AKeyPair keyPair;
	private final ACell ownerKey;

	private SQLDatabase(ALatticeCursor<Index<Keyword, ACell>> cursor,
			AString dbName, AKeyPair keyPair, ACell ownerKey) {
		super(cursor);
		this.dbName = dbName;
		this.keyPair = keyPair;
		this.ownerKey = ownerKey;
	}

	/**
	 * Creates a new empty SQL database with the given name and signing key pair.
	 * The owner key defaults to the key pair's AccountKey.
	 *
	 * @param name Database name
	 * @param keyPair Key pair for signing this node's replica
	 * @return New SQLDatabase instance
	 */
	public static SQLDatabase create(String name, AKeyPair keyPair) {
		return create(name, keyPair, keyPair.getAccountKey());
	}

	/**
	 * Creates a new empty SQL database with the given name, signing key pair, and owner key.
	 * The owner key determines the identity under which this replica is published.
	 *
	 * @param name Database name
	 * @param keyPair Key pair for signing this node's replica
	 * @param ownerKey Owner identity (AccountKey, Address, or AString DID)
	 * @return New SQLDatabase instance
	 */
	public static SQLDatabase create(String name, AKeyPair keyPair, ACell ownerKey) {
		ALatticeCursor<Index<Keyword, ACell>> cursor =
			Cursors.createLattice(DATABASE_LATTICE);
		return new SQLDatabase(cursor, Strings.create(name), keyPair, ownerKey);
	}

	/**
	 * Creates a persisted SQL database backed by a local-only {@link NodeServer}.
	 * The NodeServer handles Etch persistence; call {@code server.close()} to flush
	 * and shut down.
	 *
	 * <p>Usage:
	 * <pre>
	 * EtchStore store = EtchStore.createTemp();
	 * NodeServer&lt;?&gt; server = SQLDatabase.createNodeServer(store);
	 * server.launch();
	 * SQLDatabase db = SQLDatabase.connect(server.getCursor(), "mydb");
	 * // ... use db ...
	 * server.persistSnapshot(server.getLocalValue()); // flush to Etch
	 * server.close();
	 * </pre>
	 *
	 * @param store Store for persistence (e.g. {@code EtchStore.createTemp()})
	 * @return NodeServer configured for SQL database lattice (local-only, no network)
	 */
	public static NodeServer<?> createNodeServer(AStore store) {
		return new NodeServer<>(DATABASE_MAP_LATTICE, store, NodeConfig.port(-1));
	}

	/**
	 * Connects to a database within an existing cursor chain (e.g. from a NodeServer).
	 * The parent cursor should be post-SignedCursor (navigated past the signing boundary).
	 * Signing and replication are handled by the cursor chain.
	 *
	 * @param parent Parent lattice cursor (e.g. a SignedCursor for the owner's db map)
	 * @param name Database name to connect to
	 * @return New SQLDatabase connected to the cursor chain
	 */
	public static SQLDatabase connect(ALatticeCursor<?> parent, String name) {
		AString dbName = Strings.create(name);
		ALatticeCursor<Index<Keyword, ACell>> cursor = parent.path(dbName);
		if (cursor.get() == null) {
			cursor.set(DATABASE_LATTICE.zero());
		}
		return new SQLDatabase(cursor, dbName, null, null);
	}

	/**
	 * Creates a forked copy of this database for transaction isolation.
	 * The fork reads from a snapshot at fork time; writes accumulate locally.
	 * Call {@link #sync()} on the fork to merge changes back into the parent.
	 * Discard the fork (don't sync) for rollback.
	 *
	 * @return Forked SQLDatabase instance
	 */
	public SQLDatabase fork() {
		return new SQLDatabase(cursor.fork(), dbName, keyPair, ownerKey);
	}

	/**
	 * Returns the LatticeTables facade for performing table operations.
	 *
	 * @return LatticeTables instance
	 */
	public SQLSchema tables() {
		return new SQLSchema(cursor.path(KEY_TABLES));
	}

	/**
	 * Returns the database name.
	 *
	 * @return Database name
	 */
	public AString getName() {
		return dbName;
	}

	/**
	 * Returns this node's owner key in the OwnerLattice.
	 *
	 * @return Owner key (AccountKey, Address, or AString DID), or null if connected
	 */
	public ACell getOwnerKey() {
		return ownerKey;
	}

	/**
	 * Returns this node's key pair used for signing.
	 *
	 * @return Signing key pair, or null if connected
	 */
	public AKeyPair getKeyPair() {
		return keyPair;
	}

	/**
	 * Returns this owner's signed database map containing this database.
	 * The signed value is a map of database names to database state.
	 *
	 * <p>Only available in standalone mode (created via {@link #create}).
	 *
	 * @return SignedData wrapping a map of {dbName → database state}
	 * @throws IllegalStateException if no signing key is available (connected mode)
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public SignedData<AHashMap<AString, Index<Keyword, ACell>>> getSignedState() {
		if (keyPair == null) throw new IllegalStateException("No signing key (connected mode)");
		AHashMap<AString, Index<Keyword, ACell>> dbMap =
			(AHashMap) Maps.of(dbName, cursor.get());
		return keyPair.signData(dbMap);
	}

	/**
	 * Exports this node's replica as an owner map entry suitable for lattice merge
	 * at the :sql level. Returns a map with a single entry:
	 * this node's owner key → signed({dbName → database state}).
	 *
	 * <p>Only available in standalone mode (created via {@link #create}).
	 *
	 * @return Owner map with this node's signed contribution
	 * @throws IllegalStateException if no signing key is available (connected mode)
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public AHashMap<ACell, SignedData<AHashMap<AString, Index<Keyword, ACell>>>> exportReplica() {
		return (AHashMap) Maps.of(ownerKey, getSignedState());
	}

	/**
	 * Merges replicas from a remote owner map into this node's local store.
	 * Uses {@link OwnerLattice} to verify signatures and owner-key matching,
	 * then absorbs verified remote data into the local database state.
	 *
	 * <p>Only available in standalone mode (created via {@link #create}).
	 *
	 * @param remoteOwnerMap Map of owner-key → signed({dbName → database state}) from remote nodes
	 * @return Number of replicas successfully merged
	 * @throws IllegalStateException if no signing key is available (connected mode)
	 */
	public long mergeReplicas(AHashMap<ACell, SignedData<AHashMap<AString, Index<Keyword, ACell>>>> remoteOwnerMap) {
		if (remoteOwnerMap == null || remoteOwnerMap.isEmpty()) return 0;
		if (keyPair == null) throw new IllegalStateException("No signing key (connected mode)");

		// Use OwnerLattice to verify signatures and owner-key matching
		LatticeContext ctx = LatticeContext.create(null, keyPair);
		AHashMap<ACell, SignedData<AHashMap<AString, Index<Keyword, ACell>>>> verified =
			OWNER_LATTICE.merge(ctx, Maps.empty(), remoteOwnerMap);

		// Absorb verified remote data into local store
		long merged = 0;
		for (var entry : verified.entrySet()) {
			if (ownerKey.equals(entry.getKey())) continue; // skip self
			AHashMap<AString, Index<Keyword, ACell>> dbMap = entry.getValue().getValue();
			if (dbMap == null) continue;
			@SuppressWarnings("unchecked")
			Index<Keyword, ACell> remoteState = (Index<Keyword, ACell>) dbMap.get(dbName);
			if (remoteState != null) {
				cursor.merge(remoteState);
				merged++;
			}
		}
		return merged;
	}
}
