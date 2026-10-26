package convex.db.lattice;

import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

import convex.core.crypto.AKeyPair;
import convex.core.data.ABlobLike;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;
import convex.db.ConvexDB;
import convex.lattice.ALatticeComponent;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Cursors;

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
 * <p>The database's tables are its {@code :tables} section, merged by the rule of
 * the {@link SQLSchema} that {@link #tables()} supplies. Other sections belong to
 * other components: incoming ones are ignored and those already held are kept.
 * Connecting to a database and reading it create nothing; the first changed write
 * creates it. Writes are stamped with the clock this database passes to its tables.
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

	private final AString dbName;
	private final AKeyPair keyPair;
	private final ACell ownerKey;
	private final BiPredicate<ACell, AccountKey> ownerVerifier;
	private final Supplier<CVMLong> clock;

	/**
	 * Binds a database to its cursor. Construction reads and writes nothing.
	 *
	 * @param cursor Cursor of the database's state, which may be absent
	 * @param dbName Database name
	 * @param keyPair Key pair for signing this node's replica, or null when connected
	 * @param ownerKey Owner identity of this node's replica, or null when connected
	 * @param ownerVerifier Rule authorising signers for indirect owners, or null to refuse them
	 * @param clock Clock that stamps writes to this database's tables
	 */
	protected SQLDatabase(AForkableCursor<Index<Keyword, ACell>> cursor, AString dbName, AKeyPair keyPair,
			ACell ownerKey, BiPredicate<ACell, AccountKey> ownerVerifier, Supplier<CVMLong> clock) {
		super(cursor);
		this.dbName = dbName;
		this.keyPair = keyPair;
		this.ownerKey = ownerKey;
		this.ownerVerifier = ownerVerifier;
		this.clock = Objects.requireNonNull(clock, "Clock must not be null");
	}

	/**
	 * Creates a new empty standalone SQL database with the given name and signing key pair.
	 * The owner key defaults to the key pair's AccountKey, and writes are stamped with
	 * the runtime clock.
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
	 * signing key pair, and owner key, stamping writes with the runtime clock. With no
	 * owner verifier, an owner must denote an account key directly (an AccountKey or
	 * self-certifying {@code did:key}); use the verifier overload for an Address or
	 * another DID method.
	 *
	 * @param name Database name
	 * @param keyPair Key pair for signing this node's replica
	 * @param ownerKey Owner identity (AccountKey or {@code did:key})
	 * @return New SQLDatabase instance
	 */
	public static SQLDatabase create(String name, AKeyPair keyPair, ACell ownerKey) {
		return create(name, keyPair, ownerKey, Utils::getCurrentTimestampCell);
	}

	/**
	 * Creates a new empty standalone SQL database with the given name, signing key
	 * pair, owner key and clock, and no owner verifier.
	 *
	 * @param name Database name
	 * @param keyPair Key pair for signing this node's replica
	 * @param ownerKey Owner identity (AccountKey or {@code did:key})
	 * @param clock Clock that stamps writes to this database's tables
	 * @return New SQLDatabase instance
	 */
	public static SQLDatabase create(String name, AKeyPair keyPair, ACell ownerKey, Supplier<CVMLong> clock) {
		AForkableCursor<Index<Keyword, ACell>> cursor = Cursors.root(Index.none());
		return new SQLDatabase(cursor, Strings.create(name), keyPair, ownerKey, null, clock);
	}

	/**
	 * Creates a new empty standalone SQL database whose indirect owners (Address, or a
	 * DID that is not {@code did:key}) are authorised by the given rule, stamping writes
	 * with the runtime clock. The rule governs both incoming replicas and this node's own
	 * export, and supports multi-key owners and key rotation through an updatable
	 * authority view. It must be thread-safe and use locally available authority data.
	 * Direct key owners are always checked by equality.
	 *
	 * @param name Database name
	 * @param keyPair Key pair for signing this node's replica
	 * @param ownerKey Owner identity (AccountKey, Address, or AString DID)
	 * @param ownerVerifier Rule taking (owner, signer key), true if that key may sign for the owner
	 * @return New SQLDatabase instance
	 */
	public static SQLDatabase create(String name, AKeyPair keyPair, ACell ownerKey,
			BiPredicate<ACell, AccountKey> ownerVerifier) {
		return create(name, keyPair, ownerKey, ownerVerifier, Utils::getCurrentTimestampCell);
	}

	/**
	 * Creates a new empty standalone SQL database with an owner verifier and a clock,
	 * as {@link #create(String, AKeyPair, ACell, BiPredicate)}.
	 *
	 * @param name Database name
	 * @param keyPair Key pair for signing this node's replica
	 * @param ownerKey Owner identity (AccountKey, Address, or AString DID)
	 * @param ownerVerifier Rule taking (owner, signer key), true if that key may sign for the owner
	 * @param clock Clock that stamps writes to this database's tables
	 * @return New SQLDatabase instance
	 */
	public static SQLDatabase create(String name, AKeyPair keyPair, ACell ownerKey,
			BiPredicate<ACell, AccountKey> ownerVerifier, Supplier<CVMLong> clock) {
		AForkableCursor<Index<Keyword, ACell>> cursor = Cursors.root(Index.none());
		return new SQLDatabase(cursor, Strings.create(name), keyPair, ownerKey,
			Objects.requireNonNull(ownerVerifier), clock);
	}

	/**
	 * Connects to a database within an existing cursor chain at the database-map
	 * level, stamping writes with the runtime clock. Signing and replication are
	 * handled by the cursor chain. Connecting creates nothing.
	 *
	 * @param parent Parent lattice cursor at the database-map level
	 * @param name Database name to connect to
	 * @return New SQLDatabase connected to the cursor chain
	 */
	@SuppressWarnings("unchecked")
	public static SQLDatabase connect(AForkableCursor<?> parent, String name) {
		AString dbName = Strings.create(name);
		var databases = (AForkableCursor<AHashMap<AString, Index<Keyword, ACell>>>) parent;
		return new SQLDatabase(databases.withDefault(Maps.empty()).path(dbName), dbName, null, null, null,
			Utils::getCurrentTimestampCell);
	}

	/**
	 * Connects to a database nested beneath a ConvexDB component, at
	 * {@link ConvexDB#path(AString)} and with its clock. Connecting creates nothing.
	 *
	 * @param parent Containing ConvexDB component
	 * @param name Database name to connect to
	 * @return New SQLDatabase over the containing component's cursor chain
	 */
	public static SQLDatabase connect(ConvexDB parent, AString name) {
		if (parent==null) throw new IllegalArgumentException("Parent ConvexDB must not be null");
		return new SQLDatabase(parent.path(name), name, null, null, null, parent.getClock());
	}

	/**
	 * Connects to a database nested beneath a ConvexDB component. Connecting creates nothing.
	 *
	 * @param parent Containing ConvexDB component
	 * @param name Database name to connect to
	 * @return New SQLDatabase over the containing component's cursor chain
	 */
	public static SQLDatabase connect(ConvexDB parent, String name) {
		return connect(parent, Strings.create(name));
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
		return rebind(storage.fork());
	}

	/** Subclasses override this to return their own type, so forks keep their tables and clock. */
	@Override
	protected SQLDatabase rebind(AForkableCursor<Index<Keyword, ACell>> cursor) {
		return new SQLDatabase(cursor,dbName,keyPair,ownerKey,ownerVerifier,clock);
	}

	/** Returns the clock that stamps writes to this database's tables. */
	public Supplier<CVMLong> getClock() {
		return clock;
	}

	/**
	 * Returns the cursor of this database's {@code :tables} section, for constructing
	 * the table store. A missing database is created only by a changed write.
	 *
	 * @return Cursor of the table store, which may be absent
	 */
	public AForkableCursor<Index<AString, AVector<ACell>>> tablesPath() {
		return cursor().withDefault(Index.none()).path(ConvexDB.KEY_TABLES);
	}

	/**
	 * Returns the table store. Construction reads and writes nothing. Merge, delivery
	 * and table operations all use this component, so an override supplying another
	 * {@link SQLSchema} changes how the tables merge.
	 *
	 * @return SQLSchema instance for this database, bound to {@link #tablesPath()}, with its clock
	 */
	public SQLSchema tables() {
		return new SQLSchema(tablesPath(), clock);
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
	 * Returns the rule authorising signers for indirect owners.
	 *
	 * @return Owner verifier, or null if indirect owners are refused
	 */
	public BiPredicate<ACell, AccountKey> getOwnerVerifier() {
		return ownerVerifier;
	}

	/**
	 * Returns this owner's signed database map containing this database.
	 * The signed value is a map of database names to database state. It must pass
	 * {@link #acceptsReplica}, so this node never exports a replica that it would
	 * refuse from a peer.
	 *
	 * <p>Only available in standalone mode (created via {@link #create}).
	 *
	 * @return SignedData wrapping a map of {dbName → database state}
	 * @throws IllegalStateException if no signing key is available (connected mode),
	 *         or the key is not authorised for this node's owner
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public SignedData<AHashMap<AString, Index<Keyword, ACell>>> getSignedState() {
		if (keyPair == null) throw new IllegalStateException("No signing key (connected mode)");
		AHashMap<AString, Index<Keyword, ACell>> dbMap =
			(AHashMap) Maps.of(dbName, cursor().get());
		SignedData<AHashMap<AString, Index<Keyword, ACell>>> signed = keyPair.signData(dbMap);
		if (!acceptsReplica(ownerKey, signed)) throw new IllegalStateException("Signing key is not authorised for database owner");
		return signed;
	}

	/**
	 * Exports this node's replica as an owner map entry suitable for lattice merge
	 * at the :sql level. Returns a map with a single entry:
	 * this node's owner key → signed({dbName → database state}).
	 *
	 * <p>Only available in standalone mode (created via {@link #create}).
	 *
	 * @return Owner map with this node's signed contribution
	 * @throws IllegalStateException if no signing key is available (connected mode),
	 *         or the key is not authorised for this node's owner
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public AHashMap<ACell, SignedData<AHashMap<AString, Index<Keyword, ACell>>>> exportReplica() {
		return (AHashMap) Maps.of(ownerKey, getSignedState());
	}

	/**
	 * Merges replicas from a remote owner map into this node's local store.
	 * Each replica must pass {@link #acceptsReplica}; its state for this database
	 * is then merged with this database's own rule.
	 *
	 * <p>Only available in standalone mode (created via {@link #create}).
	 *
	 * @param remoteOwnerMap Map of owner-key → signed({dbName → database state}) from remote nodes
	 * @return Number of replicas successfully merged
	 * @throws IllegalStateException if no signing key is available (connected mode)
	 */
	@SuppressWarnings("unchecked")
	public long mergeReplicas(AHashMap<ACell, SignedData<AHashMap<AString, Index<Keyword, ACell>>>> remoteOwnerMap) {
		if (remoteOwnerMap == null || remoteOwnerMap.isEmpty()) return 0;
		if (keyPair == null) throw new IllegalStateException("No signing key (connected mode)");

		long merged = 0;
		for (var entry : remoteOwnerMap.entrySet()) {
			ACell owner = entry.getKey();
			if (ownerKey.equals(owner)) continue; // skip self
			// Foreign entries stay untyped until the replica is accepted
			ACell replica = entry.getValue();
			if (!(replica instanceof SignedData<?> signed) || !acceptsReplica(owner, signed)) continue;
			if (!(signed.getValue() instanceof AHashMap<?, ?> dbMap)) continue;
			if (!(dbMap.get(dbName) instanceof Index<?, ?> remoteState)) continue;
			cursor().updateAndGet(own -> mergeValues(own, (Index<Keyword, ACell>) remoteState));
			merged++;
		}
		return merged;
	}

	/**
	 * Whether a replica is accepted from an owner, applied to incoming replicas and to
	 * this node's own export. Its signer must be authorised for the owner and its
	 * signature must verify. An owner that denotes an account key directly (AccountKey,
	 * {@code did:key}) must be that signer. Any other owner (Address, other DIDs) is
	 * authorised only by the owner verifier, and refused when there is none. Override to
	 * apply another ownership rule or to replicate selectively.
	 *
	 * @param owner Owner identity the replica was published under
	 * @param replica Signed replica
	 * @return true if the replica may be merged
	 */
	protected boolean acceptsReplica(ACell owner, SignedData<?> replica) {
		if (owner == null) return false;
		AccountKey signer = replica.getAccountKey();
		AccountKey direct = LatticeContext.ownerAccountKey(owner);
		boolean authorised = (direct != null) ? direct.equals(signer)
			: (ownerVerifier != null) && ownerVerifier.test(owner, signer);
		return authorised && replica.checkSignature();
	}

	/**
	 * This database's merge rule: its {@code :tables} section merges with the rule of
	 * {@link #tables()}, other incoming sections are ignored and those of {@code own} kept.
	 * It is a pure function that reads and writes no state, and an absent database stays
	 * absent when nothing is accepted.
	 *
	 * @param own Established database state, kept on ties; null if absent
	 * @param other Database state to merge in; null if absent
	 * @return Merged database state
	 */
	@SuppressWarnings("unchecked")
	public Index<Keyword, ACell> mergeValues(Index<Keyword, ACell> own, Index<Keyword, ACell> other) {
		ACell incoming = other;
		if (!(incoming instanceof Index<?, ?> index) || !(index.get(ConvexDB.KEY_TABLES) instanceof Index<?, ?> tables)) return own;
		ACell current = (own == null) ? null : own.get(ConvexDB.KEY_TABLES);
		if (current != null && !(current instanceof Index)) return own;
		Index<AString, AVector<ACell>> merged = tables().mergeValues(
			(Index<AString, AVector<ACell>>) current, (Index<AString, AVector<ACell>>) tables);
		if (Utils.equals(merged, current)) return own;
		return ((own == null) ? Index.<Keyword, ACell>none() : own).assoc(ConvexDB.KEY_TABLES, merged);
	}

	@Override
	public Index<Keyword, ACell> merge(Index<Keyword, ACell> incoming) {
		return cursor().updateAndGet(own -> mergeValues(own, incoming));
	}

	/**
	 * Reconciles this component's draft with this database's rule. Only the tables
	 * belong to this component, so the destination's other sections are kept as they
	 * are, including the removal of one still present in the draft's stale snapshot.
	 */
	@Override
	protected Index<Keyword, ACell> mergePublication(Index<Keyword, ACell> own, Index<Keyword, ACell> other) {
		var merged = mergeValues(own, other);
		if (other == null) return merged;
		ACell tables = (merged == null) ? null : merged.get(ConvexDB.KEY_TABLES);
		return (tables == null) ? other : other.assoc(ConvexDB.KEY_TABLES, tables);
	}

	/** The {@code :tables} section resolves to {@link #tables()}, matched as the index matches keys. */
	@SuppressWarnings("unchecked")
	@Override
	protected <T extends ACell> ALatticeComponent<T> child(ACell key) {
		if (!(key instanceof ABlobLike<?> blob) || !ConvexDB.KEY_TABLES.toBlob().equals(blob.toBlob())) return null;
		return (ALatticeComponent<T>) (ALatticeComponent<?>) tables();
	}

	/** Deliveries to other sections are ignored: they belong to other components. */
	@Override
	protected Index<Keyword, ACell> mergeAtPath(ACell value, ACell... path) {
		return cursor().get();
	}
}
