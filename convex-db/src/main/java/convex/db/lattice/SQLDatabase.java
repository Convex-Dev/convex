package convex.db.lattice;

import java.util.function.Predicate;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;

/**
 * A named SQL database within the global lattice, with per-owner signed replicas.
 *
 * <p>Sits at the lattice path {@code :sql / <owner-key>} and handles:
 * <ul>
 *   <li>Signing this node's database with its key pair</li>
 *   <li>Merging selected remote replicas into the local store (merge-on-write)</li>
 *   <li>Rejecting replicas with invalid signatures</li>
 * </ul>
 *
 * <p>Lattice path structure:
 * <pre>
 * :sql → OwnerLattice → SignedLattice → MapLattice → TableStoreLattice
 *         owner-key → signed(db-name → table-store)
 * </pre>
 *
 * <p>The owner key may be an AccountKey (Ed25519 public key), a Convex Address,
 * or a DID string.
 *
 * <p>Usage:
 * <pre>
 * AKeyPair keyPair = AKeyPair.generate();
 * SQLDatabase db = SQLDatabase.create("mydb", keyPair);
 *
 * // Use the LatticeTables facade for operations
 * db.tables().createTable("users", new String[]{"id", "name", "email"});
 * db.tables().insert("users", key, values);
 *
 * // Merge replicas from other nodes
 * db.mergeReplicas(remoteOwnerMap);
 * </pre>
 */
public class SQLDatabase {

	private final AString dbName;
	private final AKeyPair keyPair;
	private final ACell ownerKey;
	private final LatticeTables tables;

	private SQLDatabase(AString dbName, AKeyPair keyPair, ACell ownerKey, LatticeTables tables) {
		this.dbName = dbName;
		this.keyPair = keyPair;
		this.ownerKey = ownerKey;
		this.tables = tables;
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
		LatticeTables tables = LatticeTables.create();
		return new SQLDatabase(Strings.create(name), keyPair, ownerKey, tables);
	}

	/**
	 * Returns the LatticeTables facade for performing table operations.
	 *
	 * @return LatticeTables instance
	 */
	public LatticeTables tables() {
		return tables;
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
	 * @return Owner key (AccountKey, Address, or AString DID)
	 */
	public ACell getOwnerKey() {
		return ownerKey;
	}

	/**
	 * Returns this node's key pair used for signing.
	 *
	 * @return Signing key pair
	 */
	public AKeyPair getKeyPair() {
		return keyPair;
	}

	/**
	 * Returns this owner's signed database map containing this database.
	 * The signed value is a map of database names to table store state.
	 *
	 * @return SignedData wrapping a map of {dbName → table store state}
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public SignedData<AHashMap<AString, Index<AString, AVector<ACell>>>> getSignedState() {
		AHashMap<AString, Index<AString, AVector<ACell>>> dbMap =
			(AHashMap) Maps.of(dbName, tables.cursor().get());
		return keyPair.signData(dbMap);
	}

	/**
	 * Exports this node's replica as an owner map entry suitable for lattice merge
	 * at the :sql level. Returns a map with a single entry:
	 * this node's owner key → signed({dbName → table store state}).
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
	 * @param remoteOwnerMap Map of owner-key → signed({dbName → table store}) from remote nodes
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
	 * @param remoteOwnerMap Map of owner-key → signed({dbName → table store}) from remote nodes
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

			// Verify signer matches owner key (for AccountKey owners)
			// For other owner types, this simple merge model doesn't do advanced verification
			if (remoteOwnerKey instanceof AccountKey ownerAK) {
				if (!ownerAK.equals(signedDbMap.getAccountKey())) continue;
			}

			// Extract this database from the remote owner's database map
			AHashMap<AString, Index<AString, AVector<ACell>>> dbMap = signedDbMap.getValue();
			if (dbMap == null) continue;

			@SuppressWarnings("unchecked")
			Index<AString, AVector<ACell>> remoteState = (Index<AString, AVector<ACell>>) dbMap.get(dbName);
			if (remoteState != null) {
				tables.cursor().merge(remoteState);
				merged++;
			}
		}
		return merged;
	}
}
