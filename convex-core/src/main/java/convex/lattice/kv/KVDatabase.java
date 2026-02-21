package convex.lattice.kv;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.generic.MapLattice;
import convex.lattice.generic.OwnerLattice;

/**
 * A named KV database within the global lattice, with per-owner signed replicas.
 *
 * <p>Sits at the lattice path {@code :kv / <owner-key>} and handles:
 * <ul>
 *   <li>Signing this node's database map with its key pair</li>
 *   <li>Merging remote replicas into the local store (absorption merge)</li>
 *   <li>Rejecting replicas with invalid signatures via {@link OwnerLattice}</li>
 * </ul>
 *
 * <p>Lattice path structure:
 * <pre>
 * :kv → OwnerLattice → SignedLattice → MapLattice → KVStoreLattice
 *         owner-key → signed(db-name → KV store state)
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
 * KVDatabase db = KVDatabase.create("mydb", keyPair);
 * db.kv().set("key", value);
 * db.mergeReplicas(remoteOwnerMap);
 *
 * // Connected (e.g. from a SignedCursor in a NodeServer)
 * KVDatabase db = KVDatabase.connect(signedCursor, "mydb");
 * db.kv().set("key", value);
 * </pre>
 *
 * @see <a href="https://docs.convex.world/cad/037_kv_database">CAD037: KV Database</a>
 * @see <a href="https://docs.convex.world/cad/038_lattice_auth">CAD038: Lattice Authentication</a>
 */
public class KVDatabase {

	/**
	 * The OwnerLattice structure for KV databases in the global lattice.
	 * Structure: OwnerLattice → SignedLattice → MapLattice → KVStoreLattice
	 */
	public static final OwnerLattice<AHashMap<AString, Index<AString, AVector<ACell>>>>
		OWNER_LATTICE = OwnerLattice.create(MapLattice.create(KVStoreLattice.INSTANCE));

	private final AString dbName;
	private final AKeyPair keyPair;
	private final ACell ownerKey;
	private final LatticeKV kv;

	private KVDatabase(AString dbName, AKeyPair keyPair, ACell ownerKey, LatticeKV kv) {
		this.dbName = dbName;
		this.keyPair = keyPair;
		this.ownerKey = ownerKey;
		this.kv = kv;
	}

	/**
	 * Creates a new empty database with the given name and signing key pair.
	 * The owner key defaults to the key pair's AccountKey.
	 *
	 * @param name Database name
	 * @param keyPair Key pair for signing this node's replica
	 * @return New KVDatabase instance
	 */
	public static KVDatabase create(String name, AKeyPair keyPair) {
		return new KVDatabase(Strings.create(name), keyPair, keyPair.getAccountKey(), LatticeKV.create());
	}

	/**
	 * Creates a new empty database with the given name, signing key pair, and replica ID.
	 * The owner key defaults to the key pair's AccountKey.
	 *
	 * @param name Database name
	 * @param keyPair Key pair for signing this node's replica
	 * @param replicaID Replica ID for PN-counter operations
	 * @return New KVDatabase instance
	 */
	public static KVDatabase create(String name, AKeyPair keyPair, AString replicaID) {
		return create(name, keyPair, keyPair.getAccountKey(), replicaID);
	}

	/**
	 * Creates a new empty database with the given name, signing key pair, owner key, and replica ID.
	 * The owner key determines the identity under which this replica is published in the OwnerLattice.
	 * It may be an AccountKey, Address, or DID string.
	 *
	 * @param name Database name
	 * @param keyPair Key pair for signing this node's replica
	 * @param ownerKey Owner identity in the OwnerLattice (AccountKey, Address, or AString DID)
	 * @param replicaID Replica ID for PN-counter operations
	 * @return New KVDatabase instance
	 */
	public static KVDatabase create(String name, AKeyPair keyPair, ACell ownerKey, AString replicaID) {
		LatticeKV kv = LatticeKV.create(replicaID);
		return new KVDatabase(Strings.create(name), keyPair, ownerKey, kv);
	}

	/**
	 * Connects to a database within an existing cursor chain (e.g. from a NodeServer).
	 * The parent cursor should be post-SignedCursor (navigated past the signing boundary).
	 * Signing and replication are handled by the cursor chain.
	 *
	 * @param parent Parent lattice cursor (e.g. a SignedCursor for the owner's db map)
	 * @param name Database name to connect to
	 * @return New KVDatabase connected to the cursor chain
	 */
	public static KVDatabase connect(ALatticeCursor<?> parent, String name) {
		AString dbName = Strings.create(name);
		ALatticeCursor<Index<AString, AVector<ACell>>> cursor = parent.path(dbName);
		if (cursor.get() == null) {
			cursor.set(KVStoreLattice.INSTANCE.zero());
		}
		LatticeKV kv = LatticeKV.connect(cursor);
		return new KVDatabase(dbName, null, null, kv);
	}

	/**
	 * Connects to a database within an existing cursor chain with a replica ID.
	 *
	 * @param parent Parent lattice cursor (e.g. a SignedCursor for the owner's db map)
	 * @param name Database name to connect to
	 * @param replicaID Replica ID for PN-counter operations
	 * @return New KVDatabase connected to the cursor chain
	 */
	public static KVDatabase connect(ALatticeCursor<?> parent, String name, AString replicaID) {
		AString dbName = Strings.create(name);
		ALatticeCursor<Index<AString, AVector<ACell>>> cursor = parent.path(dbName);
		if (cursor.get() == null) {
			cursor.set(KVStoreLattice.INSTANCE.zero());
		}
		LatticeKV kv = LatticeKV.connect(cursor, replicaID);
		return new KVDatabase(dbName, null, null, kv);
	}

	/**
	 * Returns the LatticeKV facade for performing KV operations on this database.
	 */
	public LatticeKV kv() {
		return kv;
	}

	/**
	 * Returns the database name.
	 */
	public AString getName() {
		return dbName;
	}

	/**
	 * Returns this node's owner key in the OwnerLattice.
	 * May be an AccountKey, Address, or AString DID.
	 *
	 * @return Owner key, or null if connected mode
	 */
	public ACell getOwnerKey() {
		return ownerKey;
	}

	/**
	 * Returns this node's key pair used for signing.
	 *
	 * @return Signing key pair, or null if connected mode
	 */
	public AKeyPair getKeyPair() {
		return keyPair;
	}

	/**
	 * Returns this owner's signed database map containing this database.
	 * The signed value is a map of database names to KV store state.
	 *
	 * <p>Only available in standalone mode (created via {@link #create}).
	 *
	 * @return SignedData wrapping a map of {dbName → KV store state}
	 * @throws IllegalStateException if no signing key is available (connected mode)
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public SignedData<AHashMap<AString, Index<AString, AVector<ACell>>>> getSignedState() {
		if (keyPair == null) throw new IllegalStateException("No signing key (connected mode)");
		AHashMap<AString, Index<AString, AVector<ACell>>> dbMap =
			(AHashMap) Maps.of(dbName, kv.cursor().get());
		return keyPair.signData(dbMap);
	}

	/**
	 * Exports this node's replica as an owner map entry suitable for lattice merge
	 * at the :kv level. Returns a map with a single entry:
	 * this node's owner key → signed({dbName → KV store state}).
	 *
	 * <p>Only available in standalone mode (created via {@link #create}).
	 *
	 * @return Owner map with this node's signed contribution
	 * @throws IllegalStateException if no signing key is available (connected mode)
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public AHashMap<ACell, SignedData<AHashMap<AString, Index<AString, AVector<ACell>>>>> exportReplica() {
		return (AHashMap) Maps.of(ownerKey, getSignedState());
	}

	/**
	 * Merges replicas from a remote owner map into this node's local store.
	 * Uses {@link OwnerLattice} to verify signatures and owner-key matching,
	 * then absorbs verified remote data into the local KV store.
	 *
	 * <p>Only available in standalone mode (created via {@link #create}).
	 *
	 * @param remoteOwnerMap Map of owner-key → signed({dbName → KV state}) from remote nodes
	 * @return Number of replicas successfully merged
	 * @throws IllegalStateException if no signing key is available (connected mode)
	 */
	public long mergeReplicas(AHashMap<ACell, SignedData<AHashMap<AString, Index<AString, AVector<ACell>>>>> remoteOwnerMap) {
		if (remoteOwnerMap == null || remoteOwnerMap.isEmpty()) return 0;
		if (keyPair == null) throw new IllegalStateException("No signing key (connected mode)");

		// Use OwnerLattice to verify signatures and owner-key matching
		LatticeContext ctx = LatticeContext.create(null, keyPair);
		AHashMap<ACell, SignedData<AHashMap<AString, Index<AString, AVector<ACell>>>>> verified =
			OWNER_LATTICE.merge(ctx, Maps.empty(), remoteOwnerMap);

		// Absorb verified remote data into local store
		long merged = 0;
		for (var entry : verified.entrySet()) {
			if (ownerKey.equals(entry.getKey())) continue; // skip self
			AHashMap<AString, Index<AString, AVector<ACell>>> dbMap = entry.getValue().getValue();
			if (dbMap == null) continue;

			Index<AString, AVector<ACell>> remoteState = (Index<AString, AVector<ACell>>) dbMap.get(dbName);
			if (remoteState != null) {
				kv.cursor().merge(remoteState);
				merged++;
			}
		}
		return merged;
	}
}
