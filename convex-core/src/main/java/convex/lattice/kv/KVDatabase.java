package convex.lattice.kv;

import java.util.function.Predicate;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;

/**
 * A named KV database within the global lattice, with per-owner signed replicas.
 *
 * <p>Sits at the lattice path {@code :kv / <owner-key>} and handles:
 * <ul>
 *   <li>Signing this node's database map with its key pair</li>
 *   <li>Merging selected remote replicas into the local store (merge-on-write)</li>
 *   <li>Rejecting replicas with invalid signatures</li>
 * </ul>
 *
 * <p>Lattice path structure:
 * <pre>
 * :kv → OwnerLattice → SignedLattice → MapLattice → KVStoreLattice
 *         owner-key → signed(db-name → KV store state)
 * </pre>
 *
 * <p>The owner key may be an AccountKey (Ed25519 public key), a Convex Address,
 * or a DID string. See {@link convex.lattice.LatticeContext#verifyOwner} for
 * the verification model.
 *
 * <p>Usage:
 * <pre>
 * AKeyPair keyPair = AKeyPair.generate();
 * KVDatabase db = KVDatabase.create("mydb", keyPair);
 *
 * // Use the LatticeKV facade for operations
 * db.kv().set("key", value);
 *
 * // Merge replicas from other nodes
 * db.mergeReplicas(remoteOwnerMap);
 *
 * // Merge only from trusted owners
 * db.mergeReplicas(remoteOwnerMap, owner -&gt; trustedOwners.contains(owner));
 * </pre>
 *
 * @see <a href="https://docs.convex.world/cad/037_kv_database">CAD037: KV Database</a>
 * @see <a href="https://docs.convex.world/cad/038_lattice_auth">CAD038: Lattice Authentication</a>
 */
public class KVDatabase {

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
		return create(name, keyPair, "default");
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
	public static KVDatabase create(String name, AKeyPair keyPair, String replicaID) {
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
	public static KVDatabase create(String name, AKeyPair keyPair, ACell ownerKey, String replicaID) {
		LatticeKV kv = LatticeKV.create(replicaID);
		return new KVDatabase(Strings.create(name), keyPair, ownerKey, kv);
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
	 */
	public ACell getOwnerKey() {
		return ownerKey;
	}

	/**
	 * Returns this node's key pair used for signing.
	 */
	public AKeyPair getKeyPair() {
		return keyPair;
	}

	/**
	 * Returns this owner's signed database map containing this database.
	 * The signed value is a map of database names to KV store state.
	 *
	 * @return SignedData wrapping a map of {dbName → KV store state}
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public SignedData<AHashMap<AString, Index<AString, AVector<ACell>>>> getSignedState() {
		AHashMap<AString, Index<AString, AVector<ACell>>> dbMap =
			(AHashMap) Maps.of(dbName, kv.cursor().get());
		return keyPair.signData(dbMap);
	}

	/**
	 * Exports this node's replica as an owner map entry suitable for lattice merge
	 * at the :kv level. Returns a map with a single entry:
	 * this node's owner key → signed({dbName → KV store state}).
	 *
	 * @return Owner map with this node's signed contribution
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public AHashMap<ACell, SignedData<AHashMap<AString, Index<AString, AVector<ACell>>>>> exportReplica() {
		return (AHashMap) Maps.of(ownerKey, getSignedState());
	}

	/**
	 * Merges all replicas from a remote owner map into this node's local store.
	 * Rejects replicas with invalid signatures.
	 *
	 * <p>This is the merge-on-write model: remote values are absorbed into the
	 * local KV store via KVStoreLattice merge, and the result is owned by this node.
	 *
	 * @param remoteOwnerMap Map of owner-key → signed({dbName → KV state}) from remote nodes
	 * @return Number of replicas successfully merged
	 */
	public long mergeReplicas(AHashMap<ACell, SignedData<AHashMap<AString, Index<AString, AVector<ACell>>>>> remoteOwnerMap) {
		return mergeReplicas(remoteOwnerMap, key -> true);
	}

	/**
	 * Merges selected replicas from a remote owner map into this node's local store.
	 * Only merges replicas whose owner key passes the filter predicate.
	 * Rejects replicas with invalid signatures regardless of filter.
	 *
	 * <p>The owner key may be any ACell type supported by the OwnerLattice:
	 * AccountKey, Address, or AString (DID). The filter predicate receives the
	 * owner key as-is.
	 *
	 * @param remoteOwnerMap Map of owner-key → signed({dbName → KV state}) from remote nodes
	 * @param ownerFilter Predicate to select which owners' replicas to merge
	 * @return Number of replicas successfully merged
	 */
	public long mergeReplicas(
			AHashMap<ACell, SignedData<AHashMap<AString, Index<AString, AVector<ACell>>>>> remoteOwnerMap,
			Predicate<ACell> ownerFilter) {
		if (remoteOwnerMap == null || remoteOwnerMap.isEmpty()) return 0;

		long merged = 0;
		for (var entry : remoteOwnerMap.entrySet()) {
			ACell remoteOwnerKey = entry.getKey();
			SignedData<AHashMap<AString, Index<AString, AVector<ACell>>>> signedDbMap = entry.getValue();

			// Skip our own replica
			if (ownerKey.equals(remoteOwnerKey)) continue;

			// Apply filter
			if (!ownerFilter.test(remoteOwnerKey)) continue;

			// Validate signature
			if (!signedDbMap.checkSignature()) continue;

			// Extract this database from the remote owner's database map
			AHashMap<AString, Index<AString, AVector<ACell>>> dbMap = signedDbMap.getValue();
			if (dbMap == null) continue;

			@SuppressWarnings("unchecked")
			Index<AString, AVector<ACell>> remoteState = (Index<AString, AVector<ACell>>) dbMap.get(dbName);
			if (remoteState != null) {
				kv.cursor().merge(remoteState);
				merged++;
			}
		}
		return merged;
	}
}
