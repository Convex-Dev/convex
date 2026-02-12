package convex.peer.signing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import convex.core.crypto.AESGCM;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.crypto.HKDF;
import convex.core.crypto.Hashing;
import convex.core.crypto.util.Multikey;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.json.JWT;
import convex.lattice.cursor.ACursor;

/**
 * Encrypted key management service for Convex peers.
 *
 * Operates on an arbitrary {@link ACursor} pointing to the {@code :signing}
 * subtree of a peer's local lattice data. The cursor abstraction decouples
 * key management from the choice of persistence/replication mechanism — the
 * server layer controls how the cursor is backed (EtchStore, in-memory, etc.).
 *
 * <h2>Data Layout</h2>
 * The cursor should point at an {@code AHashMap<Keyword, ACell>} with:
 * <pre>
 *   :secret  → ABlob (encryptionSecret, encrypted with peer key)
 *   :keys    → Index&lt;Hash, ABlob&gt; (lookup-hash → encrypted seed)
 *   :users   → Index&lt;Hash, ABlob&gt; (identity-hash → encrypted key list)
 *   :version → CVMLong (schema version)
 * </pre>
 *
 * <h2>Security Model</h2>
 * <ul>
 *   <li>A random 256-bit {@code encryptionSecret} is generated once and stored
 *       encrypted with the peer key. All key material derives from this secret.</li>
 *   <li>Individual keys are encrypted with HKDF-derived wrapping keys bound to
 *       (identity, publicKey, passphrase). You must know all three to decrypt.</li>
 *   <li>The user index reveals key ownership (public keys per identity) but not
 *       key material — encrypted with a key derived from identity alone.</li>
 * </ul>
 *
 * @see convex.core.crypto.HKDF
 * @see convex.core.crypto.AESGCM
 */
public class SigningService {

	// Keyword constants for the :signing subtree
	static final Keyword KEY_SECRET  = Keyword.intern("secret");
	static final Keyword KEY_KEYS    = Keyword.intern("keys");
	static final Keyword KEY_USERS   = Keyword.intern("users");
	static final Keyword KEY_VERSION = Keyword.intern("version");

	// HKDF info strings
	private static final byte[] INFO_SECRET = "convex-signing-secret-v1".getBytes();
	private static final byte[] INFO_KEY    = "convex-signing-service-v1".getBytes();
	private static final byte[] INFO_USER   = "convex-user-index-v1".getBytes();

	private final AKeyPair peerKeyPair;
	private final ACursor<ACell> cursor;
	private byte[] encryptionSecret;

	/**
	 * Creates a SigningService operating on the given cursor.
	 *
	 * The cursor should point to the {@code :signing} subtree (or an empty/null
	 * value if this is a first start). Call {@link #init()} to initialise or
	 * recover the encryption secret.
	 *
	 * @param peerKeyPair The peer's Ed25519 key pair (for encrypting the secret)
	 * @param cursor Cursor to the :signing data subtree
	 */
	public SigningService(AKeyPair peerKeyPair, ACursor<ACell> cursor) {
		if (peerKeyPair == null) throw new IllegalArgumentException("Peer key pair required");
		if (cursor == null) throw new IllegalArgumentException("Cursor required");
		this.peerKeyPair = peerKeyPair;
		this.cursor = cursor;
	}

	/**
	 * Initialises the signing service. On first start, generates a random
	 * encryptionSecret and writes the initial structure. On subsequent starts,
	 * loads and decrypts the existing secret.
	 */
	public void init() {
		ACell current = cursor.get();
		if (current == null || !(current instanceof AHashMap)) {
			// First start — generate secret and initialise structure
			encryptionSecret = new byte[32];
			new java.security.SecureRandom().nextBytes(encryptionSecret);

			ABlob encryptedSecret = encryptSecret(encryptionSecret);
			AHashMap<Keyword, ACell> initial = Maps.of(
				KEY_SECRET,  encryptedSecret,
				KEY_KEYS,    Index.none(),
				KEY_USERS,   Index.none(),
				KEY_VERSION, CVMLong.ONE
			);
			cursor.set(initial);
		} else {
			// Load existing secret
			@SuppressWarnings("unchecked")
			AHashMap<Keyword, ACell> data = (AHashMap<Keyword, ACell>) current;
			ABlob encryptedSecret = (ABlob) data.get(KEY_SECRET);
			if (encryptedSecret == null) throw new IllegalStateException("Missing encryption secret");
			encryptionSecret = decryptSecret(encryptedSecret);
		}
	}

	/**
	 * Creates a new Ed25519 key pair, encrypts it, and stores it under the
	 * given identity and passphrase.
	 *
	 * @param identity Caller identity (DID string)
	 * @param passphrase Passphrase for key encryption
	 * @return Public key of the created key pair
	 */
	public AccountKey createKey(String identity, String passphrase) {
		checkInitialised();
		AKeyPair newKP = AKeyPair.generate();
		AccountKey publicKey = newKP.getAccountKey();
		byte[] seed = newKP.getSeed().getBytes();

		storeKey(identity, publicKey, passphrase, seed);
		addToUserIndex(identity, publicKey);

		// Zero the seed bytes
		Arrays.fill(seed, (byte) 0);

		return publicKey;
	}

	/**
	 * Lists the public keys associated with the given identity.
	 *
	 * @param identity Caller identity (DID string)
	 * @return List of public keys (excluding tombstoned keys), may be empty
	 */
	public List<AccountKey> listKeys(String identity) {
		checkInitialised();
		List<AccountKey> result = new ArrayList<>();

		ABlob encryptedIndex = getUserIndexEntry(identity);
		if (encryptedIndex == null) return result;

		byte[] userKey = deriveUserIndexKey(identity);
		byte[] decrypted;
		try {
			decrypted = AESGCM.decrypt(userKey, encryptedIndex.getBytes());
		} finally {
			Arrays.fill(userKey, (byte) 0);
		}

		// Decode the key list — stored as concatenated 32-byte public keys
		// with tombstones represented as 32 zero bytes
		for (int i = 0; i + AccountKey.LENGTH <= decrypted.length; i += AccountKey.LENGTH) {
			byte[] keyBytes = Arrays.copyOfRange(decrypted, i, i + AccountKey.LENGTH);
			AccountKey ak = AccountKey.create(Blob.wrap(keyBytes));
			if (!isZero(keyBytes)) {
				result.add(ak);
			}
		}

		return result;
	}

	/**
	 * Gets the peer key pair used by this signing service.
	 */
	public AKeyPair getPeerKeyPair() {
		return peerKeyPair;
	}

	/**
	 * Signs arbitrary bytes using a stored key.
	 *
	 * @param identity Caller identity (DID string)
	 * @param publicKey Public key identifying which key to use
	 * @param passphrase Passphrase for key decryption
	 * @param message Bytes to sign
	 * @return Ed25519 signature, or null if key not found
	 */
	public ASignature sign(String identity, AccountKey publicKey, String passphrase, ABlob message) {
		checkInitialised();
		byte[] seed = loadKey(identity, publicKey, passphrase);
		if (seed == null) return null;

		try {
			AKeyPair kp = AKeyPair.create(seed);
			return kp.sign(message.toFlatBlob());
		} finally {
			Arrays.fill(seed, (byte) 0);
		}
	}

	/**
	 * Creates a self-signed EdDSA JWT for the given key.
	 *
	 * The JWT has {@code sub} and {@code iss} set to {@code did:key:<multikey>},
	 * an {@code exp} claim based on the lifetime, and optionally an {@code aud}
	 * claim if audience is provided. Extra claims are merged into the payload.
	 *
	 * @param identity Caller identity (DID string)
	 * @param publicKey Public key identifying which key to use
	 * @param passphrase Passphrase for key decryption
	 * @param audience Audience claim value, or null to omit
	 * @param extraClaims Additional claims to merge, or null
	 * @param lifetimeSeconds Token lifetime in seconds from now
	 * @return Encoded JWT string, or null if key not found
	 */
	public AString getSelfSignedJWT(String identity, AccountKey publicKey, String passphrase,
			String audience, AMap<AString, ACell> extraClaims, long lifetimeSeconds) {
		checkInitialised();
		byte[] seed = loadKey(identity, publicKey, passphrase);
		if (seed == null) return null;

		try {
			AKeyPair kp = AKeyPair.create(seed);

			// Build did:key identifier from public key
			AString didKey = Strings.create("did:key:" + Multikey.encodePublicKey(publicKey));

			long now = System.currentTimeMillis() / 1000;
			AMap<AString, ACell> claims = Maps.of(
				Strings.create("sub"), didKey,
				Strings.create("iss"), didKey,
				Strings.create("iat"), CVMLong.create(now),
				Strings.create("exp"), CVMLong.create(now + lifetimeSeconds)
			);

			if (audience != null) {
				claims = claims.assoc(Strings.create("aud"), Strings.create(audience));
			}

			if (extraClaims != null) {
				long n = extraClaims.count();
				for (long i = 0; i < n; i++) {
					var entry = extraClaims.entryAt(i);
					claims = claims.assoc(entry.getKey(), entry.getValue());
				}
			}

			return JWT.signPublic(claims, kp);
		} finally {
			Arrays.fill(seed, (byte) 0);
		}
	}

	// ==================== Internal Methods ====================

	private void checkInitialised() {
		if (encryptionSecret == null) throw new IllegalStateException("SigningService not initialised");
	}

	/**
	 * Stores an encrypted seed in the :keys index and returns the lookup hash.
	 */
	void storeKey(String identity, AccountKey publicKey, String passphrase, byte[] seed) {
		Hash lookupHash = computeLookupHash(identity, publicKey, passphrase);
		byte[] wrappingKey = deriveKeyWrappingKey(identity, publicKey, passphrase);
		byte[] encrypted = AESGCM.encrypt(wrappingKey, seed);
		Arrays.fill(wrappingKey, (byte) 0);

		ABlob encryptedBlob = Blob.wrap(encrypted);

		cursor.updateAndGet(v -> {
			@SuppressWarnings("unchecked")
			AHashMap<Keyword, ACell> data = (AHashMap<Keyword, ACell>) v;
			@SuppressWarnings("unchecked")
			Index<Hash, ABlob> keys = (Index<Hash, ABlob>) data.get(KEY_KEYS);
			if (keys == null) keys = Index.none();
			keys = keys.assoc(lookupHash, encryptedBlob);
			return data.assoc(KEY_KEYS, keys);
		});
	}

	/**
	 * Loads and decrypts a seed from the :keys index.
	 *
	 * @return Decrypted seed bytes, or null if not found
	 */
	byte[] loadKey(String identity, AccountKey publicKey, String passphrase) {
		Hash lookupHash = computeLookupHash(identity, publicKey, passphrase);

		@SuppressWarnings("unchecked")
		AHashMap<Keyword, ACell> data = (AHashMap<Keyword, ACell>) cursor.get();
		if (data == null) return null;

		@SuppressWarnings("unchecked")
		Index<Hash, ABlob> keys = (Index<Hash, ABlob>) data.get(KEY_KEYS);
		if (keys == null) return null;

		ABlob encrypted = keys.get(lookupHash);
		if (encrypted == null) return null;

		byte[] wrappingKey = deriveKeyWrappingKey(identity, publicKey, passphrase);
		try {
			return AESGCM.decrypt(wrappingKey, encrypted.getBytes());
		} finally {
			Arrays.fill(wrappingKey, (byte) 0);
		}
	}

	/**
	 * Adds a public key to the user index for the given identity.
	 */
	void addToUserIndex(String identity, AccountKey publicKey) {
		Hash userHash = computeUserHash(identity);
		byte[] userKey = deriveUserIndexKey(identity);

		// Read existing key list (or empty)
		ABlob existing = getUserIndexEntry(identity);
		byte[] keyList;
		if (existing != null) {
			try {
				keyList = AESGCM.decrypt(userKey, existing.getBytes());
			} catch (Exception e) {
				// Corrupted entry — start fresh
				keyList = new byte[0];
			}
		} else {
			keyList = new byte[0];
		}

		// Append the new public key (32 bytes)
		byte[] newKeyList = new byte[keyList.length + AccountKey.LENGTH];
		System.arraycopy(keyList, 0, newKeyList, 0, keyList.length);
		publicKey.getBytes(newKeyList, keyList.length);

		// Encrypt and store
		byte[] encrypted = AESGCM.encrypt(userKey, newKeyList);
		Arrays.fill(userKey, (byte) 0);
		ABlob encryptedBlob = Blob.wrap(encrypted);

		cursor.updateAndGet(v -> {
			@SuppressWarnings("unchecked")
			AHashMap<Keyword, ACell> data = (AHashMap<Keyword, ACell>) v;
			@SuppressWarnings("unchecked")
			Index<Hash, ABlob> users = (Index<Hash, ABlob>) data.get(KEY_USERS);
			if (users == null) users = Index.none();
			users = users.assoc(userHash, encryptedBlob);
			return data.assoc(KEY_USERS, users);
		});
	}

	/**
	 * Gets the encrypted user index entry for the given identity.
	 */
	private ABlob getUserIndexEntry(String identity) {
		Hash userHash = computeUserHash(identity);

		@SuppressWarnings("unchecked")
		AHashMap<Keyword, ACell> data = (AHashMap<Keyword, ACell>) cursor.get();
		if (data == null) return null;

		@SuppressWarnings("unchecked")
		Index<Hash, ABlob> users = (Index<Hash, ABlob>) data.get(KEY_USERS);
		if (users == null) return null;

		return users.get(userHash);
	}

	// ==================== Crypto Helpers ====================

	/**
	 * Encrypts the encryptionSecret with the peer's key.
	 * Uses HKDF(peerSeed, info: "convex-signing-secret-v1") as the wrapping key.
	 */
	ABlob encryptSecret(byte[] secret) {
		byte[] peerSeed = peerKeyPair.getSeed().getBytes();
		byte[] wrappingKey = HKDF.derive256(peerSeed, null, INFO_SECRET);
		byte[] encrypted = AESGCM.encrypt(wrappingKey, secret);
		Arrays.fill(wrappingKey, (byte) 0);
		return Blob.wrap(encrypted);
	}

	/**
	 * Decrypts the encryptionSecret with the peer's key.
	 */
	byte[] decryptSecret(ABlob encryptedSecret) {
		byte[] peerSeed = peerKeyPair.getSeed().getBytes();
		byte[] wrappingKey = HKDF.derive256(peerSeed, null, INFO_SECRET);
		try {
			return AESGCM.decrypt(wrappingKey, encryptedSecret.getBytes());
		} finally {
			Arrays.fill(wrappingKey, (byte) 0);
		}
	}

	/**
	 * Computes the lookup hash for a key: SHA-256(identity ‖ publicKey ‖ passphrase)
	 */
	static Hash computeLookupHash(String identity, AccountKey publicKey, String passphrase) {
		byte[] idBytes = identity.getBytes();
		byte[] pkBytes = publicKey.getBytes();
		byte[] ppBytes = passphrase.getBytes();
		byte[] combined = new byte[idBytes.length + pkBytes.length + ppBytes.length];
		System.arraycopy(idBytes, 0, combined, 0, idBytes.length);
		System.arraycopy(pkBytes, 0, combined, idBytes.length, pkBytes.length);
		System.arraycopy(ppBytes, 0, combined, idBytes.length + pkBytes.length, ppBytes.length);
		return Hashing.sha256(combined);
	}

	/**
	 * Computes the user index hash: SHA-256(identity)
	 */
	static Hash computeUserHash(String identity) {
		return Hashing.sha256(identity.getBytes());
	}

	/**
	 * Derives the wrapping key for an individual signing key.
	 * HKDF(encryptionSecret, salt: identity ‖ publicKey ‖ passphrase, info: "convex-signing-service-v1")
	 */
	byte[] deriveKeyWrappingKey(String identity, AccountKey publicKey, String passphrase) {
		byte[] idBytes = identity.getBytes();
		byte[] pkBytes = publicKey.getBytes();
		byte[] ppBytes = passphrase.getBytes();
		byte[] salt = new byte[idBytes.length + pkBytes.length + ppBytes.length];
		System.arraycopy(idBytes, 0, salt, 0, idBytes.length);
		System.arraycopy(pkBytes, 0, salt, idBytes.length, pkBytes.length);
		System.arraycopy(ppBytes, 0, salt, idBytes.length + pkBytes.length, ppBytes.length);
		return HKDF.derive256(encryptionSecret, salt, INFO_KEY);
	}

	/**
	 * Derives the wrapping key for a user index entry.
	 * HKDF(encryptionSecret, salt: identity, info: "convex-user-index-v1")
	 */
	byte[] deriveUserIndexKey(String identity) {
		return HKDF.derive256(encryptionSecret, identity.getBytes(), INFO_USER);
	}

	private static boolean isZero(byte[] bytes) {
		for (byte b : bytes) {
			if (b != 0) return false;
		}
		return true;
	}
}
