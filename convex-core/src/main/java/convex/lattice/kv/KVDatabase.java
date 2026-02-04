package convex.lattice.kv;

import java.util.function.Predicate;

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

/**
 * A named KV database within the global lattice, with per-node signed replicas.
 *
 * <p>Sits at the lattice path {@code :kv / <db-name> / <node-key>} and handles:
 * <ul>
 *   <li>Signing this node's KV store with its key pair</li>
 *   <li>Merging selected remote replicas into the local store (merge-on-write)</li>
 *   <li>Rejecting replicas with invalid signatures</li>
 * </ul>
 *
 * <p>Lattice path structure:
 * <pre>
 * :kv → MapLattice&lt;AString, OwnerLattice&lt;KVStoreLattice&gt;&gt;
 *         db-name →  node-key → signed(Index&lt;AString, KVEntry&gt;)
 * </pre>
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
 * // Merge only from trusted nodes
 * db.mergeReplicas(remoteOwnerMap, key -&gt; trustedKeys.contains(key));
 * </pre>
 */
public class KVDatabase {

	private final AString dbName;
	private final AKeyPair keyPair;
	private final AccountKey accountKey;
	private final LatticeKV kv;

	private KVDatabase(AString dbName, AKeyPair keyPair, LatticeKV kv) {
		this.dbName = dbName;
		this.keyPair = keyPair;
		this.accountKey = keyPair.getAccountKey();
		this.kv = kv;
	}

	/**
	 * Creates a new empty database with the given name and signing key pair.
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
	 *
	 * @param name Database name
	 * @param keyPair Key pair for signing this node's replica
	 * @param replicaID Replica ID for PN-counter operations
	 * @return New KVDatabase instance
	 */
	public static KVDatabase create(String name, AKeyPair keyPair, String replicaID) {
		LatticeKV kv = LatticeKV.create(replicaID);
		return new KVDatabase(Strings.create(name), keyPair, kv);
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
	 * Returns this node's account key (public key).
	 */
	public AccountKey getAccountKey() {
		return accountKey;
	}

	/**
	 * Returns this node's key pair.
	 */
	public AKeyPair getKeyPair() {
		return keyPair;
	}

	/**
	 * Returns this node's KV store as signed data.
	 *
	 * @return SignedData wrapping the current KV store state
	 */
	public SignedData<Index<AString, AVector<ACell>>> getSignedState() {
		return keyPair.signData(kv.cursor().get());
	}

	/**
	 * Exports this node's replica as an owner map entry suitable for lattice merge.
	 * Returns a map with a single entry: this node's account key → signed KV state.
	 *
	 * @return Owner map with this node's signed contribution
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public AHashMap<ACell, SignedData<Index<AString, AVector<ACell>>>> exportReplica() {
		return (AHashMap) Maps.of(accountKey, getSignedState());
	}

	/**
	 * Merges all replicas from a remote owner map into this node's local store.
	 * Rejects replicas with invalid signatures.
	 *
	 * <p>This is the merge-on-write model: remote values are absorbed into the
	 * local KV store via KVStoreLattice merge, and the result is owned by this node.
	 *
	 * @param remoteOwnerMap Map of node-key → signed KV state from remote nodes
	 * @return Number of replicas successfully merged
	 */
	public long mergeReplicas(AHashMap<ACell, SignedData<Index<AString, AVector<ACell>>>> remoteOwnerMap) {
		return mergeReplicas(remoteOwnerMap, key -> true);
	}

	/**
	 * Merges selected replicas from a remote owner map into this node's local store.
	 * Only merges replicas whose account key passes the filter predicate.
	 * Rejects replicas with invalid signatures regardless of filter.
	 *
	 * @param remoteOwnerMap Map of node-key → signed KV state from remote nodes
	 * @param replicaFilter Predicate to select which replicas to merge (by account key)
	 * @return Number of replicas successfully merged
	 */
	public long mergeReplicas(
			AHashMap<ACell, SignedData<Index<AString, AVector<ACell>>>> remoteOwnerMap,
			Predicate<AccountKey> replicaFilter) {
		if (remoteOwnerMap == null || remoteOwnerMap.isEmpty()) return 0;

		long merged = 0;
		for (var entry : remoteOwnerMap.entrySet()) {
			ACell ownerKey = entry.getKey();
			SignedData<Index<AString, AVector<ACell>>> signedState = entry.getValue();

			// Skip our own replica
			if (accountKey.equals(ownerKey)) continue;

			// Apply filter
			if (ownerKey instanceof AccountKey ak) {
				if (!replicaFilter.test(ak)) continue;
			} else {
				continue; // Skip non-AccountKey owners
			}

			// Validate signature
			if (!signedState.checkSignature()) continue;

			// Merge remote KV state into our local store
			Index<AString, AVector<ACell>> remoteState = signedState.getValue();
			if (remoteState != null) {
				kv.cursor().merge(remoteState);
				merged++;
			}
		}
		return merged;
	}
}
