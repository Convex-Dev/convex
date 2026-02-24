package convex.peer.signing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import convex.auth.jwt.JWT;
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
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
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
 *   :secret     → ABlob (encryptionSecret, encrypted with peer key)
 *   :keys       → Index&lt;Hash, ABlob&gt; (lookup-hash → encrypted seed)
 *   :identities → Index&lt;Hash, AVector&gt; (identity-hash → vector of public keys)
 *   :version    → CVMLong (schema version)
 *   :timestamp  → CVMLong (epoch millis, bumped on every mutation for LWW merge)
 * </pre>
 *
 * The {@code :identities} index is a plaintext mapping from identity hash to
 * a vector of {@link AccountKey}s. It enables {@link #listKeys(AString)} to
 * enumerate keys for a given identity. This is separate from the peer-level
 * {@code :users} registry which tracks user lifecycle and ownership.
 *
 * <h2>Security Model</h2>
 * <ul>
 *   <li>A random 256-bit {@code encryptionSecret} is generated once and stored
 *       encrypted with the peer key. All key material derives from this secret.</li>
 *   <li>Individual keys are encrypted with HKDF-derived wrapping keys bound to
 *       (identity, publicKey, passphrase). You must know all three to decrypt.</li>
 *   <li>The identity index is unencrypted — it maps identity hashes to public
 *       keys, both of which are public information.</li>
 * </ul>
 *
 * @see convex.core.crypto.HKDF
 * @see convex.core.crypto.AESGCM
 */
public class SigningService {

	// Keyword constants for the :signing subtree
	static final Keyword KEY_SECRET     = Keyword.intern("secret");
	static final Keyword KEY_KEYS       = Keyword.intern("keys");
	static final Keyword KEY_IDENTITIES = Keyword.intern("identities");
	static final Keyword KEY_VERSION    = Keyword.intern("version");
	static final Keyword KEY_TIMESTAMP  = Keyword.intern("timestamp");

	// HKDF info strings
	private static final byte[] INFO_SECRET = "convex-signing-secret-v1".getBytes();
	private static final byte[] INFO_KEY    = "convex-signing-service-v1".getBytes();

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
				KEY_SECRET,     encryptedSecret,
				KEY_KEYS,       Index.none(),
				KEY_IDENTITIES, Index.none(),
				KEY_VERSION,    CVMLong.ONE
			);
			initial = initial.assoc(KEY_TIMESTAMP, CVMLong.create(System.currentTimeMillis()));
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
	public AccountKey createKey(AString identity, AString passphrase) {
		checkInitialised();
		AKeyPair newKP = AKeyPair.generate();
		AccountKey publicKey = newKP.getAccountKey();
		byte[] seed = newKP.getSeed().getBytes();

		storeKey(identity, publicKey, passphrase, seed);
		addToKeyIndex(identity, publicKey);

		// Zero the seed bytes
		Arrays.fill(seed, (byte) 0);

		return publicKey;
	}

	/**
	 * Lists the public keys associated with the given identity.
	 *
	 * @param identity Caller identity (DID string)
	 * @return List of public keys, may be empty
	 */
	public List<AccountKey> listKeys(AString identity) {
		checkInitialised();
		List<AccountKey> result = new ArrayList<>();
		Hash idHash = computeIdentityHash(identity);

		@SuppressWarnings("unchecked")
		AHashMap<Keyword, ACell> data = (AHashMap<Keyword, ACell>) cursor.get();
		if (data == null) return result;

		@SuppressWarnings("unchecked")
		Index<Hash, ACell> identities = (Index<Hash, ACell>) data.get(KEY_IDENTITIES);
		if (identities == null) return result;

		@SuppressWarnings("unchecked")
		AVector<ACell> keys = (AVector<ACell>) identities.get(idHash);
		if (keys == null) return result;

		for (long i = 0; i < keys.count(); i++) {
			AccountKey ak = (AccountKey) keys.get(i);
			if (ak != null) result.add(ak);
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
	public ASignature sign(AString identity, AccountKey publicKey, AString passphrase, ABlob message) {
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
	public AString getSelfSignedJWT(AString identity, AccountKey publicKey, AString passphrase,
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

	/**
	 * Imports an existing Ed25519 seed, encrypts it, and stores it under the
	 * given identity and passphrase.
	 *
	 * If the key already exists in the key index for this identity, the
	 * encrypted seed is overwritten but no duplicate entry is created.
	 *
	 * @param identity Caller identity (DID string)
	 * @param seed Raw 32-byte Ed25519 seed
	 * @param passphrase Passphrase for key encryption
	 * @return Public key derived from the seed
	 */
	public AccountKey importKey(AString identity, ABlob seed, AString passphrase) {
		checkInitialised();
		AKeyPair kp = AKeyPair.create(seed.getBytes());
		AccountKey publicKey = kp.getAccountKey();

		storeKey(identity, publicKey, passphrase, seed.getBytes());

		// Only add to key index if not already present
		if (!listKeys(identity).contains(publicKey)) {
			addToKeyIndex(identity, publicKey);
		}

		return publicKey;
	}

	/**
	 * Exports (decrypts) the seed for a stored key.
	 *
	 * @param identity Caller identity (DID string)
	 * @param publicKey Public key identifying which key to export
	 * @param passphrase Passphrase for key decryption
	 * @return Decrypted seed as a Blob, or null if key not found
	 */
	public ABlob exportKey(AString identity, AccountKey publicKey, AString passphrase) {
		checkInitialised();
		byte[] seed = loadKey(identity, publicKey, passphrase);
		if (seed == null) return null;
		return Blob.wrap(seed);
	}

	/**
	 * Deletes a key from the store. Removes the encrypted seed from the
	 * {@code :keys} index and removes the public key from the identity's
	 * key index.
	 *
	 * @param identity Caller identity (DID string)
	 * @param publicKey Public key identifying which key to delete
	 * @param passphrase Passphrase (required to compute the lookup hash)
	 */
	public void deleteKey(AString identity, AccountKey publicKey, AString passphrase) {
		checkInitialised();
		removeFromKeys(identity, publicKey, passphrase);
		removeFromKeyIndex(identity, publicKey);
	}

	/**
	 * Changes the passphrase for a stored key. Decrypts the seed with the old
	 * passphrase, removes the old encrypted entry, and re-encrypts with the
	 * new passphrase. The key index is unchanged (public key identity is the same).
	 *
	 * @param identity Caller identity (DID string)
	 * @param publicKey Public key identifying which key to re-wrap
	 * @param oldPass Current passphrase
	 * @param newPass New passphrase
	 * @throws IllegalArgumentException if the old passphrase is wrong
	 */
	public void changePassphrase(AString identity, AccountKey publicKey, AString oldPass, AString newPass) {
		checkInitialised();
		byte[] seed = loadKey(identity, publicKey, oldPass);
		if (seed == null) throw new IllegalArgumentException("Cannot decrypt key with provided credentials");

		try {
			removeFromKeys(identity, publicKey, oldPass);
			storeKey(identity, publicKey, newPass, seed);
		} finally {
			Arrays.fill(seed, (byte) 0);
		}
	}

	// ==================== Internal Methods ====================

	private void checkInitialised() {
		if (encryptionSecret == null) throw new IllegalStateException("SigningService not initialised");
	}

	/**
	 * Stores an encrypted seed in the :keys index.
	 */
	void storeKey(AString identity, AccountKey publicKey, AString passphrase, byte[] seed) {
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
			data = data.assoc(KEY_KEYS, keys);
			data = data.assoc(KEY_TIMESTAMP, CVMLong.create(System.currentTimeMillis()));
			return data;
		});
	}

	/**
	 * Loads and decrypts a seed from the :keys index.
	 *
	 * @return Decrypted seed bytes, or null if not found
	 */
	byte[] loadKey(AString identity, AccountKey publicKey, AString passphrase) {
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
	 * Removes an encrypted seed from the :keys index.
	 */
	void removeFromKeys(AString identity, AccountKey publicKey, AString passphrase) {
		Hash lookupHash = computeLookupHash(identity, publicKey, passphrase);
		cursor.updateAndGet(v -> {
			@SuppressWarnings("unchecked")
			AHashMap<Keyword, ACell> data = (AHashMap<Keyword, ACell>) v;
			@SuppressWarnings("unchecked")
			Index<Hash, ABlob> keys = (Index<Hash, ABlob>) data.get(KEY_KEYS);
			if (keys == null) return v;
			keys = keys.dissoc(lookupHash);
			data = data.assoc(KEY_KEYS, keys);
			data = data.assoc(KEY_TIMESTAMP, CVMLong.create(System.currentTimeMillis()));
			return data;
		});
	}

	/**
	 * Adds a public key to the identity's key index.
	 */
	void addToKeyIndex(AString identity, AccountKey publicKey) {
		Hash idHash = computeIdentityHash(identity);
		cursor.updateAndGet(v -> {
			@SuppressWarnings("unchecked")
			AHashMap<Keyword, ACell> data = (AHashMap<Keyword, ACell>) v;
			@SuppressWarnings("unchecked")
			Index<Hash, ACell> identities = (Index<Hash, ACell>) data.get(KEY_IDENTITIES);
			if (identities == null) identities = Index.none();

			@SuppressWarnings("unchecked")
			AVector<ACell> keys = (AVector<ACell>) identities.get(idHash);
			if (keys == null) keys = Vectors.empty();
			keys = keys.conj(publicKey);

			identities = identities.assoc(idHash, keys);
			data = data.assoc(KEY_IDENTITIES, identities);
			data = data.assoc(KEY_TIMESTAMP, CVMLong.create(System.currentTimeMillis()));
			return data;
		});
	}

	/**
	 * Removes a public key from the identity's key index.
	 */
	void removeFromKeyIndex(AString identity, AccountKey publicKey) {
		Hash idHash = computeIdentityHash(identity);
		cursor.updateAndGet(v -> {
			@SuppressWarnings("unchecked")
			AHashMap<Keyword, ACell> data = (AHashMap<Keyword, ACell>) v;
			@SuppressWarnings("unchecked")
			Index<Hash, ACell> identities = (Index<Hash, ACell>) data.get(KEY_IDENTITIES);
			if (identities == null) return v;

			@SuppressWarnings("unchecked")
			AVector<ACell> keys = (AVector<ACell>) identities.get(idHash);
			if (keys == null) return v;

			// Rebuild vector without the deleted key
			AVector<ACell> newKeys = Vectors.empty();
			for (long i = 0; i < keys.count(); i++) {
				ACell k = keys.get(i);
				if (!publicKey.equals(k)) {
					newKeys = newKeys.conj(k);
				}
			}

			identities = identities.assoc(idHash, newKeys);
			data = data.assoc(KEY_IDENTITIES, identities);
			data = data.assoc(KEY_TIMESTAMP, CVMLong.create(System.currentTimeMillis()));
			return data;
		});
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
	 * Computes the lookup hash for a key: SHA-256(identity || publicKey || passphrase)
	 */
	static Hash computeLookupHash(AString identity, AccountKey publicKey, AString passphrase) {
		ABlob combined = identity.toBlob().append(publicKey).append(passphrase.toBlob());
		return Hashing.sha256(combined);
	}

	/**
	 * Computes the identity hash: SHA-256(identity)
	 */
	static Hash computeIdentityHash(AString identity) {
		return Hashing.sha256(identity);
	}

	/**
	 * Derives the wrapping key for an individual signing key.
	 * HKDF(encryptionSecret, salt: identity || publicKey || passphrase, info: "convex-signing-service-v1")
	 */
	byte[] deriveKeyWrappingKey(AString identity, AccountKey publicKey, AString passphrase) {
		ABlob salt = identity.toBlob().append(publicKey).append(passphrase.toBlob());
		return HKDF.derive256(encryptionSecret, salt.getBytes(), INFO_KEY);
	}
}
