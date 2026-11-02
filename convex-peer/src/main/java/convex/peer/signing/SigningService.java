package convex.peer.signing;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import convex.auth.did.DID;
import convex.auth.jwt.JWT;
import convex.core.crypto.AESGCM;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.crypto.HKDF;
import convex.core.crypto.Hashing;
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
import convex.lattice.ALocalComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Persister;

/**
 * Encrypted key store for a peer's signing service, kept as the peer's local state at
 * {@code [:local <peer key> :signing]}.
 *
 * <pre>
 * {:timestamp  t
 *  :version    1
 *  :secret     &lt;encryption secret, wrapped with the peer key&gt;
 *  :keys       {&lt;lookup hash&gt; &lt;encrypted seed&gt;}
 *  :identities {&lt;identity hash&gt; [&lt;public key&gt; ...]}}
 * </pre>
 *
 * <ul>
 *   <li>A random 256-bit encryption secret is created with the first stored key and
 *       kept wrapped with the peer key. All key material derives from it.</li>
 *   <li>Each key's seed is encrypted with a wrapping key derived from the secret and
 *       (identity, public key, passphrase), and found by a lookup hash keyed with the
 *       secret, so the record alone gives no way to test a passphrase.</li>
 *   <li>The identity index maps the SHA-256 of each identity to its public keys, so
 *       {@link #listKeys(AString)} needs no passphrase.</li>
 * </ul>
 *
 * <p>Each operation is one atomic edit of a private copy of the record, as for any
 * {@link ALocalComponent}. The service neither syncs, signs nor persists by itself:
 * the application hosting it decides when to sync the record into the peer's slot
 * and whether to persist it. Keys outlive this instance only once synced into a host
 * that retains its root.</p>
 *
 * @see convex.core.crypto.HKDF
 * @see convex.core.crypto.AESGCM
 */
public class SigningService extends ALocalComponent {

	/** Keyword naming this service's record in the peer's {@code :local} slot. */
	public static final Keyword KEY_SIGNING = Keyword.intern("signing");

	static final Keyword KEY_SECRET     = Keyword.intern("secret");
	static final Keyword KEY_KEYS       = Keyword.intern("keys");
	static final Keyword KEY_IDENTITIES = Keyword.intern("identities");
	static final Keyword KEY_VERSION    = Keyword.intern("version");

	private static final CVMLong VERSION = CVMLong.ONE;

	// HKDF info strings
	private static final byte[] INFO_SECRET = "convex-signing-secret-v1".getBytes();
	private static final byte[] INFO_KEY    = "convex-signing-service-v1".getBytes();
	private static final byte[] INFO_LOOKUP = "convex-signing-lookup-v1".getBytes();

	private static final SecureRandom RANDOM = new SecureRandom();

	private final AKeyPair peerKeyPair;

	/**
	 * Binds the signing record of a peer under the supplied root, never persisted by
	 * itself.
	 *
	 * @param root Root cursor of the containing application
	 * @param peerKeyPair The peer's key pair, which owns and signs the slot and wraps the secret
	 * @param clock Supplies the record's {@code :timestamp} for each edit
	 */
	public SigningService(AForkableCursor<Index<Keyword,ACell>> root, AKeyPair peerKeyPair, Supplier<CVMLong> clock) {
		this(root, peerKeyPair, clock, null);
	}

	/**
	 * Binds the signing record of a peer under the supplied root.
	 *
	 * @param root Root cursor of the containing application
	 * @param peerKeyPair The peer's key pair, which owns and signs the slot and wraps the secret
	 * @param clock Supplies the record's {@code :timestamp} for each edit
	 * @param persister Persists the private copy, such as {@code host::persist}, or null if it is never persisted
	 */
	public SigningService(AForkableCursor<Index<Keyword,ACell>> root, AKeyPair peerKeyPair, Supplier<CVMLong> clock,
			Persister<AHashMap<Keyword,ACell>> persister) {
		super(root, KEY_SIGNING, Objects.requireNonNull(peerKeyPair, "Peer key pair required").getAccountKey(),
			peerKeyPair::signData, clock, persister);
		this.peerKeyPair = peerKeyPair;
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
		AKeyPair newKP = AKeyPair.generate();
		byte[] seed = newKP.getSeed().getBytes();
		try {
			return storeKey(identity, passphrase, seed);
		} finally {
			Arrays.fill(seed, (byte) 0);
		}
	}

	/**
	 * Lists the public keys associated with the given identity.
	 *
	 * @param identity Caller identity (DID string)
	 * @return List of public keys, may be empty
	 */
	public List<AccountKey> listKeys(AString identity) {
		AVector<AccountKey> keys = identities(cursor().get()).get(computeIdentityHash(identity));
		List<AccountKey> result = new ArrayList<>();
		if (keys != null) {
			for (AccountKey key : keys) result.add(key);
		}
		return result;
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
		byte[] seed = loadKey(identity, publicKey, passphrase);
		if (seed == null) return null;

		try {
			AKeyPair kp = AKeyPair.create(seed);

			// Build did:key identifier from public key
			AString didKey = DID.forKey(publicKey);

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
		byte[] bytes = seed.getBytes();
		try {
			return storeKey(identity, passphrase, bytes);
		} finally {
			Arrays.fill(bytes, (byte) 0);
		}
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
		Hash idHash = computeIdentityHash(identity);
		cursor().update(record -> {
			byte[] secret = secret(record);
			if (secret == null) return record;
			try {
				Hash lookupHash = lookupHash(secret, identity, publicKey, passphrase);
				return unlisted(record.assoc(KEY_KEYS, keys(record).dissoc(lookupHash)), idHash, publicKey);
			} finally {
				Arrays.fill(secret, (byte) 0);
			}
		});
	}

	/**
	 * Changes the passphrase for a stored key. Decrypts the seed with the old
	 * passphrase and replaces its entry with one encrypted under the new
	 * passphrase. The key index is unchanged (public key identity is the same).
	 *
	 * @param identity Caller identity (DID string)
	 * @param publicKey Public key identifying which key to re-wrap
	 * @param oldPass Current passphrase
	 * @param newPass New passphrase
	 * @throws IllegalArgumentException if the old passphrase is wrong
	 */
	public void changePassphrase(AString identity, AccountKey publicKey, AString oldPass, AString newPass) {
		cursor().update(record -> {
			byte[] secret = secret(record);
			byte[] seed = (secret == null) ? null : unwrap(secret, record, identity, publicKey, oldPass);
			try {
				if (seed == null) throw new IllegalArgumentException("Cannot decrypt key with provided credentials");
				Index<Hash, ABlob> keys = keys(record).dissoc(lookupHash(secret, identity, publicKey, oldPass))
					.assoc(lookupHash(secret, identity, publicKey, newPass), wrap(secret, identity, publicKey, newPass, seed));
				return record.assoc(KEY_KEYS, keys);
			} finally {
				if (secret != null) Arrays.fill(secret, (byte) 0);
				if (seed != null) Arrays.fill(seed, (byte) 0);
			}
		});
	}

	// ==================== Record Edits ====================

	/**
	 * Stores an encrypted seed and lists its public key under the identity, in one
	 * edit. The first stored key creates the encryption secret in the same edit, so
	 * concurrent first writes cannot encrypt under different secrets.
	 */
	private AccountKey storeKey(AString identity, AString passphrase, byte[] seed) {
		AccountKey publicKey = AKeyPair.create(seed).getAccountKey();
		Hash idHash = computeIdentityHash(identity);
		cursor().update(current -> {
			AHashMap<Keyword, ACell> record = withSecret(current);
			byte[] secret = secret(record);
			try {
				Index<Hash, ABlob> keys = keys(record).assoc(lookupHash(secret, identity, publicKey, passphrase),
					wrap(secret, identity, publicKey, passphrase, seed));
				return listed(record.assoc(KEY_KEYS, keys), idHash, publicKey);
			} finally {
				Arrays.fill(secret, (byte) 0);
			}
		});
		return publicKey;
	}

	/**
	 * Loads and decrypts a seed from the current record.
	 *
	 * @return Decrypted seed bytes, or null if not found
	 */
	byte[] loadKey(AString identity, AccountKey publicKey, AString passphrase) {
		AHashMap<Keyword, ACell> record = cursor().get();
		byte[] secret = secret(record);
		if (secret == null) return null;
		try {
			return unwrap(secret, record, identity, publicKey, passphrase);
		} finally {
			Arrays.fill(secret, (byte) 0);
		}
	}

	/** The record with an encryption secret, creating one if it has none. */
	private AHashMap<Keyword, ACell> withSecret(AHashMap<Keyword, ACell> record) {
		if (record.get(KEY_SECRET) instanceof ABlob) return record;
		byte[] secret = new byte[32];
		RANDOM.nextBytes(secret);
		try {
			return record.assoc(KEY_SECRET, encryptSecret(secret)).assoc(KEY_VERSION, VERSION);
		} finally {
			Arrays.fill(secret, (byte) 0);
		}
	}

	/**
	 * Decrypts the record's encryption secret, or returns null if it has none yet. Read
	 * from the record on each use, so a newer record taken in by merge is used as it is.
	 * The caller zeroes the result.
	 */
	private byte[] secret(AHashMap<Keyword, ACell> record) {
		ACell wrapped = record.get(KEY_SECRET);
		return (wrapped instanceof ABlob blob) ? decryptSecret(blob) : null;
	}

	@SuppressWarnings("unchecked")
	private static Index<Hash, ABlob> keys(AHashMap<Keyword, ACell> record) {
		ACell keys = record.get(KEY_KEYS);
		return (keys instanceof Index) ? (Index<Hash, ABlob>) keys : Index.none();
	}

	@SuppressWarnings("unchecked")
	private static Index<Hash, AVector<AccountKey>> identities(AHashMap<Keyword, ACell> record) {
		ACell identities = record.get(KEY_IDENTITIES);
		return (identities instanceof Index) ? (Index<Hash, AVector<AccountKey>>) identities : Index.none();
	}

	/** Adds a public key to an identity's list unless it is already there. */
	private static AHashMap<Keyword, ACell> listed(AHashMap<Keyword, ACell> record, Hash idHash, AccountKey publicKey) {
		Index<Hash, AVector<AccountKey>> identities = identities(record);
		AVector<AccountKey> keys = identities.get(idHash);
		if (keys == null) keys = Vectors.empty();
		if (keys.contains(publicKey)) return record;
		return record.assoc(KEY_IDENTITIES, identities.assoc(idHash, keys.conj(publicKey)));
	}

	/** Removes a public key from an identity's list, dropping the identity once it lists none. */
	private static AHashMap<Keyword, ACell> unlisted(AHashMap<Keyword, ACell> record, Hash idHash, AccountKey publicKey) {
		Index<Hash, AVector<AccountKey>> identities = identities(record);
		AVector<AccountKey> keys = identities.get(idHash);
		if (keys == null || !keys.contains(publicKey)) return record;
		AVector<AccountKey> remaining = Vectors.empty();
		for (AccountKey k : keys) {
			if (!publicKey.equals(k)) remaining = remaining.conj(k);
		}
		identities = remaining.isEmpty() ? identities.dissoc(idHash) : identities.assoc(idHash, remaining);
		return record.assoc(KEY_IDENTITIES, identities);
	}

	// ==================== Crypto Helpers ====================

	/**
	 * Encrypts the encryption secret with the peer's key.
	 * Uses HKDF(peerSeed, info: "convex-signing-secret-v1") as the wrapping key.
	 */
	ABlob encryptSecret(byte[] secret) {
		byte[] wrappingKey = peerWrappingKey();
		try {
			return Blob.wrap(AESGCM.encrypt(wrappingKey, secret));
		} finally {
			Arrays.fill(wrappingKey, (byte) 0);
		}
	}

	/**
	 * Decrypts the encryption secret with the peer's key.
	 */
	byte[] decryptSecret(ABlob encryptedSecret) {
		byte[] wrappingKey = peerWrappingKey();
		try {
			return AESGCM.decrypt(wrappingKey, encryptedSecret.getBytes());
		} finally {
			Arrays.fill(wrappingKey, (byte) 0);
		}
	}

	private byte[] peerWrappingKey() {
		byte[] peerSeed = peerKeyPair.getSeed().getBytes();
		try {
			return HKDF.derive256(peerSeed, null, INFO_SECRET);
		} finally {
			Arrays.fill(peerSeed, (byte) 0);
		}
	}

	/** Decrypts a stored seed, or returns null if the record holds none for these credentials. */
	private static byte[] unwrap(byte[] secret, AHashMap<Keyword, ACell> record,
			AString identity, AccountKey publicKey, AString passphrase) {
		ABlob encrypted = keys(record).get(lookupHash(secret, identity, publicKey, passphrase));
		if (encrypted == null) return null;
		byte[] wrappingKey = keyWrappingKey(secret, identity, publicKey, passphrase);
		try {
			return AESGCM.decrypt(wrappingKey, encrypted.getBytes());
		} finally {
			Arrays.fill(wrappingKey, (byte) 0);
		}
	}

	private static ABlob wrap(byte[] secret, AString identity, AccountKey publicKey, AString passphrase, byte[] seed) {
		byte[] wrappingKey = keyWrappingKey(secret, identity, publicKey, passphrase);
		try {
			return Blob.wrap(AESGCM.encrypt(wrappingKey, seed));
		} finally {
			Arrays.fill(wrappingKey, (byte) 0);
		}
	}

	/**
	 * Canonical byte representation of a credential triple.
	 *
	 * Uses the CAD3 value ID of a vector, so field boundaries are explicit. Plain
	 * concatenation lets a longer identity with a shorter passphrase produce the same
	 * bytes as the reverse.
	 */
	private static byte[] credentialBytes(AString identity, AccountKey publicKey, AString passphrase) {
		return Vectors.of(identity, publicKey, passphrase).getHash().getBytes();
	}

	/**
	 * Computes the lookup hash used as the :keys index key.
	 *
	 * Keyed with the encryption secret. An unkeyed hash would let anyone holding a
	 * copy of the record brute-force the passphrase offline, since the identity and
	 * public key are public information.
	 */
	private static Hash lookupHash(byte[] secret, AString identity, AccountKey publicKey, AString passphrase) {
		return Hash.wrap(HKDF.derive256(secret, credentialBytes(identity, publicKey, passphrase), INFO_LOOKUP));
	}

	/**
	 * Derives the wrapping key for an individual signing key.
	 * HKDF(encryptionSecret, salt: canonical credential bytes, info: "convex-signing-service-v1")
	 */
	private static byte[] keyWrappingKey(byte[] secret, AString identity, AccountKey publicKey, AString passphrase) {
		return HKDF.derive256(secret, credentialBytes(identity, publicKey, passphrase), INFO_KEY);
	}

	/**
	 * Computes the identity hash: SHA-256(identity)
	 */
	static Hash computeIdentityHash(AString identity) {
		return Hashing.sha256(identity);
	}
}
