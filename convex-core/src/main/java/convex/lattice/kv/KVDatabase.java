package convex.lattice.kv;

import java.util.Objects;
import java.util.function.BiPredicate;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.lattice.LatticeContext;
import convex.lattice.MapComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.generic.MapLattice;
import convex.lattice.generic.OwnerMapLattice;

/**
 * A named KV database within the global lattice, with per-owner signed replicas.
 *
 * <p>Sits at the lattice path {@code :kv / <owner-key>} and handles:
 * <ul>
 *   <li>Signing this node's database map with its key pair</li>
 *   <li>Merging remote replicas into the local store (absorption merge)</li>
 *   <li>Rejecting replicas with invalid signatures or unauthorised signers</li>
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
 *       signing supplied by that cursor's owner boundary and publication by its host</li>
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
	 * Default ownership rule for KV replicas: direct keys and self-certifying
	 * {@code did:key} owners only. Indirect owners require an explicit verifier.
	 * Structure: owner map → signed database map → KV store.
	 */
	public static final OwnerMapLattice<ACell,AHashMap<AString, Index<AString, AVector<ACell>>>>
		OWNER_LATTICE = OwnerMapLattice.create(MapLattice.create(KVStoreLattice.INSTANCE),
			(owner,key) -> key.equals(LatticeContext.ownerAccountKey(owner)));

	private final AString dbName;
	private final AKeyPair keyPair;
	private final ACell ownerKey;
	private final LatticeKV kv;
	private final OwnerMapLattice<ACell,AHashMap<AString,Index<AString,AVector<ACell>>>> owners;

	private KVDatabase(AString dbName, AKeyPair keyPair, ACell ownerKey, LatticeKV kv) {
		this(dbName,keyPair,ownerKey,kv,OWNER_LATTICE);
	}

	private KVDatabase(AString dbName,AKeyPair keyPair,ACell ownerKey,LatticeKV kv,
			OwnerMapLattice<ACell,AHashMap<AString,Index<AString,AVector<ACell>>>> owners) {
		this.dbName = dbName;
		this.keyPair = keyPair;
		this.ownerKey = ownerKey;
		this.kv = kv;
		this.owners = owners;
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
	 * The default rule accepts a direct account key or self-certifying {@code did:key}.
	 * Use the verifier overload for account addresses or other DID methods.
	 *
	 * @param name Database name
	 * @param keyPair Key pair for signing this node's replica
	 * @param ownerKey Owner identity in the OwnerLattice (AccountKey, Address, or AString DID)
	 * @param replicaID Replica ID for PN-counter operations
	 * @return New KVDatabase instance
	 */
	public static KVDatabase create(String name, AKeyPair keyPair, ACell ownerKey, AString replicaID) {
		LatticeKV kv = LatticeKV.create(replicaID);
		return new KVDatabase(Strings.create(name), Objects.requireNonNull(keyPair),
			Objects.requireNonNull(ownerKey), kv);
	}

	/**
	 * Creates a standalone database with an explicit owner/signing-key rule, used
	 * for both exported state and incoming replicas. This supports DID or account
	 * ownership and key rotation through a shared, updatable authority view.
	 * The verifier must be thread-safe and use locally available authority data.
	 * A signing key is a capability; exports fail if this rule no longer authorises it.
	 */
	public static KVDatabase create(String name,AKeyPair keyPair,ACell ownerKey,AString replicaID,
			BiPredicate<ACell,AccountKey> verifier) {
		var owners=OwnerMapLattice.create(MapLattice.<AString,Index<AString,AVector<ACell>>>create(
			KVStoreLattice.INSTANCE),Objects.requireNonNull(verifier));
		return new KVDatabase(Strings.create(name),Objects.requireNonNull(keyPair),
			Objects.requireNonNull(ownerKey),LatticeKV.create(replicaID),owners);
	}

	/**
	 * Connects to a database within an existing cursor chain (e.g. from a NodeServer).
	 * The parent cursor should be post-SignedCursor (navigated past the signing boundary).
	 * Signing belongs to that boundary; host publication remains explicit.
	 * Connecting does not write or sign state. Missing containers are created
	 * atomically by the first changed KV operation.
	 *
	 * @param parent Parent lattice cursor (e.g. a SignedCursor for the owner's db map)
	 * @param name Database name to connect to
	 * @return New KVDatabase connected to the cursor chain
	 */
	@SuppressWarnings("unchecked")
	public static KVDatabase connect(AForkableCursor<?> parent, String name) {
		AString dbName = Strings.create(name);
		var databases=(AForkableCursor<AHashMap<AString,Index<AString, AVector<ACell>>>>)parent;
		AForkableCursor<Index<AString, AVector<ACell>>> cursor = new MapComponent<>(databases).path(dbName);
		LatticeKV kv = LatticeKV.connect(cursor);
		return new KVDatabase(dbName, null, null, kv);
	}

	/**
	 * Connects to a database within an existing cursor chain with a replica ID.
	 * Like {@link #connect(AForkableCursor, String)}, opening the view is read-only.
	 *
	 * @param parent Parent lattice cursor (e.g. a SignedCursor for the owner's db map)
	 * @param name Database name to connect to
	 * @param replicaID Replica ID for PN-counter operations
	 * @return New KVDatabase connected to the cursor chain
	 */
	@SuppressWarnings("unchecked")
	public static KVDatabase connect(AForkableCursor<?> parent, String name, AString replicaID) {
		AString dbName = Strings.create(name);
		var databases=(AForkableCursor<AHashMap<AString,Index<AString, AVector<ACell>>>>)parent;
		AForkableCursor<Index<AString, AVector<ACell>>> cursor = new MapComponent<>(databases).path(dbName);
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
	 * @throws IllegalStateException if no authorised signing key is available
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public SignedData<AHashMap<AString, Index<AString, AVector<ACell>>>> getSignedState() {
		if (keyPair == null) throw new IllegalStateException("No signing key (connected mode)");
		AHashMap<AString, Index<AString, AVector<ACell>>> dbMap =
			(AHashMap) Maps.of(dbName, kv.cursor().get());
		var signed=owners.signingFor(ownerKey).sign(dbMap,keyPair::signData);
		if (signed==null) throw new IllegalStateException("Signing key is not authorised for KV owner");
		return signed;
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
	 * Uses the bound owner rule to verify signatures and owner-key matching,
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

		// The same bound rule verifies remote replicas and local exports.
		AHashMap<ACell, SignedData<AHashMap<AString, Index<AString, AVector<ACell>>>>> verified =
			owners.merge(Maps.empty(),remoteOwnerMap);

		// Absorb verified remote data into local store
		long merged = 0;
		for (var entry : verified.entrySet()) {
			if (ownerKey.equals(entry.getKey())) continue; // skip self
			AHashMap<AString, Index<AString, AVector<ACell>>> dbMap = entry.getValue().getValue();
			if (dbMap == null) continue;

			Index<AString, AVector<ACell>> remoteState = (Index<AString, AVector<ACell>>) dbMap.get(dbName);
			if (remoteState != null) {
				kv.cursor().updateAndGet(own -> KVStoreLattice.INSTANCE.merge(own,remoteState));
				merged++;
			}
		}
		return merged;
	}
}
