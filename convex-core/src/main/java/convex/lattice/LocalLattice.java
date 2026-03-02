package convex.lattice;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.lattice.generic.LWWLattice;
import convex.lattice.generic.MapLattice;
import convex.lattice.generic.OwnerLattice;

/**
 * Helper for the `:local` OwnerLattice convention.
 *
 * The `:local` key in a lattice root maps to an OwnerLattice where each peer
 * has its own signed slot keyed by AccountKey. Each slot contains an
 * {@code Index<Keyword, ACell>} — a keyword-keyed map of peer-local data.
 *
 * Structure:
 * <pre>
 *   :local → OwnerLattice
 *     peer-key-A → Signed(Index&lt;Keyword, ACell&gt;)
 *     peer-key-B → Signed(Index&lt;Keyword, ACell&gt;)
 * </pre>
 *
 * This data is intended for peer-local storage (encrypted secrets, signing
 * keys, configuration) but uses standard lattice semantics so that:
 * <ul>
 *   <li>Multiple peers can share a store without conflicts</li>
 *   <li>OwnerLattice merge preserves each peer's slot independently</li>
 *   <li>Data can be safely replicated for fault tolerance</li>
 * </ul>
 */
public class LocalLattice {

	/**
	 * The `:local` keyword used as the top-level key in the lattice root.
	 */
	public static final Keyword KEY_LOCAL = Keyword.intern("local");

	/**
	 * Value lattice for per-peer data. Uses a MapLattice that merges each
	 * keyword's value independently via LWW (timestamp-based last-write-wins).
	 *
	 * Each service (e.g. :signing) stores its own timestamped map. Concurrent
	 * updates to different services merge cleanly; updates to the same service
	 * resolve by picking the higher timestamp.
	 */
	private static final MapLattice<Keyword, ACell> VALUE_LATTICE =
			MapLattice.create(LWWLattice.INSTANCE);

	/**
	 * The OwnerLattice type for `:local` — each peer owns a signed slot
	 * containing an {@code AHashMap<Keyword, ACell>} of peer-local data.
	 */
	public static final OwnerLattice<AHashMap<Keyword, ACell>> LATTICE =
			OwnerLattice.create(VALUE_LATTICE);

	/**
	 * Gets the signed slot for a specific peer from a local lattice value.
	 *
	 * @param localValue The OwnerLattice value (map of AccountKey → SignedData)
	 * @param peerKey The peer's public key
	 * @return The peer's signed data, or null if not present
	 */
	public static SignedData<AHashMap<Keyword, ACell>> getSignedSlot(
			AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> localValue,
			AccountKey peerKey) {
		if (localValue == null) return null;
		return localValue.get(peerKey);
	}

	/**
	 * Gets the peer's local data (the unsigned value) from a local lattice value.
	 *
	 * @param localValue The OwnerLattice value
	 * @param peerKey The peer's public key
	 * @return The peer's Index&lt;Keyword, ACell&gt; data, or null if not present
	 */
	public static AHashMap<Keyword, ACell> getSlot(
			AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> localValue,
			AccountKey peerKey) {
		SignedData<AHashMap<Keyword, ACell>> signed = getSignedSlot(localValue, peerKey);
		if (signed == null) return null;
		return signed.getValue();
	}

	/**
	 * Creates a new local lattice value with data for a single peer,
	 * signed with the peer's key pair.
	 *
	 * @param keyPair The peer's key pair (for signing)
	 * @param data The peer's local data
	 * @return An OwnerLattice map with one entry
	 */
	public static AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> createSlot(
			AKeyPair keyPair,
			AHashMap<Keyword, ACell> data) {
		SignedData<AHashMap<Keyword, ACell>> signed = keyPair.signData(data);
		return Maps.of(keyPair.getAccountKey(), signed);
	}

	/**
	 * Updates a peer's slot in the local lattice value by signing and replacing
	 * the entry for the given key pair.
	 *
	 * @param localValue Existing OwnerLattice value (may be null)
	 * @param keyPair The peer's key pair
	 * @param data The new data for this peer
	 * @return Updated OwnerLattice map
	 */
	public static AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> setSlot(
			AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> localValue,
			AKeyPair keyPair,
			AHashMap<Keyword, ACell> data) {
		if (localValue == null) localValue = Maps.empty();
		SignedData<AHashMap<Keyword, ACell>> signed = keyPair.signData(data);
		return localValue.assoc(keyPair.getAccountKey(), signed);
	}

	/**
	 * Gets a specific sub-key value from a peer's local data.
	 *
	 * @param localValue The OwnerLattice value
	 * @param peerKey The peer's public key
	 * @param key The keyword key within the peer's slot
	 * @return The value, or null if not found
	 */
	public static ACell get(
			AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> localValue,
			AccountKey peerKey,
			Keyword key) {
		AHashMap<Keyword, ACell> slot = getSlot(localValue, peerKey);
		if (slot == null) return null;
		return slot.get(key);
	}

	/**
	 * Creates a KeyedLattice entry pair for use in a root lattice definition.
	 * Can be added to an existing KeyedLattice root via:
	 * <pre>
	 *   KeyedLattice root = KeyedLattice.create(
	 *       Keywords.DATA, DataLattice.INSTANCE,
	 *       LocalLattice.KEY_LOCAL, LocalLattice.LATTICE
	 *   );
	 * </pre>
	 *
	 * @return The OwnerLattice instance for `:local`
	 */
	public static OwnerLattice<AHashMap<Keyword, ACell>> getLattice() {
		return LATTICE;
	}
}
