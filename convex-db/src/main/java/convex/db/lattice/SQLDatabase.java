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
import convex.db.ConvexDB;
import convex.lattice.ALatticeComponent;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.generic.OwnerLattice;

/**
 * A named SQL database within the lattice, with per-owner signed replicas.
 *
 * <p>Represents a single database within a {@link ConvexDB} instance.
 * Each database contains a set of tables accessible via {@link #tables()}.
 *
 * <p>Two usage modes:
 * <ul>
 *   <li><b>Standalone</b> ({@link #create}): owns its own cursor, manual export/merge</li>
 *   <li><b>Connected</b> ({@link #connect}): connected to a parent cursor chain
 *       (e.g. from a {@link ConvexDB}), signing and replication handled by the chain</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 * // Via ConvexDB (preferred)
 * ConvexDB cdb = ConvexDB.create();
 * SQLDatabase db = cdb.database("mydb");
 * db.tables().createTable("users", new String[]{"id", "name", "email"});
 *
 * // Standalone (for replication)
 * SQLDatabase db = SQLDatabase.create("mydb", keyPair);
 * db.tables().insert("users", 1, "Alice", "alice@example.com");
 * db.mergeReplicas(remoteOwnerMap);
 * </pre>
 */
public class SQLDatabase extends ALatticeComponent<Index<Keyword, ACell>> {

	/**
	 * The OwnerLattice structure for SQL databases in the global lattice.
	 * Structure: OwnerLattice → SignedLattice → MapLattice → DatabaseLattice
	 */
	public static final OwnerLattice<AHashMap<AString, Index<Keyword, ACell>>>
		OWNER_LATTICE = OwnerLattice.create(ConvexDB.DATABASE_MAP_LATTICE);

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
	 * Creates a new empty standalone SQL database with the given name and signing key pair.
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
	 * Creates a new empty standalone SQL database with the given name,
	 * signing key pair, and owner key.
	 *
	 * @param name Database name
	 * @param keyPair Key pair for signing this node's replica
	 * @param ownerKey Owner identity (AccountKey, Address, or AString DID)
	 * @return New SQLDatabase instance
	 */
	public static SQLDatabase create(String name, AKeyPair keyPair, ACell ownerKey) {
		ALatticeCursor<Index<Keyword, ACell>> cursor =
			Cursors.createLattice(ConvexDB.DATABASE_LATTICE);
		return new SQLDatabase(cursor, Strings.create(name), keyPair, ownerKey);
	}

	/**
	 * Connects to a database within an existing cursor chain (e.g. from a ConvexDB).
	 * Signing and replication are handled by the cursor chain.
	 *
	 * @param parent Parent lattice cursor at the database-map level
	 * @param name Database name to connect to
	 * @return New SQLDatabase connected to the cursor chain
	 */
	public static SQLDatabase connect(ALatticeCursor<?> parent, String name) {
		AString dbName = Strings.create(name);
		ALatticeCursor<Index<Keyword, ACell>> cursor = parent.path(dbName);
		if (cursor.get() == null) {
			cursor.set(ConvexDB.DATABASE_LATTICE.zero());
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
	 * Returns the SQLSchema facade for performing table operations.
	 *
	 * @return SQLSchema instance for this database
	 */
	public SQLSchema tables() {
		return new SQLSchema(cursor.path(ConvexDB.KEY_TABLES));
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
