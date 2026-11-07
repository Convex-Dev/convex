package convex.peer.signing;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import convex.auth.jwt.JWT;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
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
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.store.MemoryStore;
import convex.core.util.Utils;
import convex.lattice.ALatticeApplication;
import convex.lattice.LatticeContext;
import convex.lattice.LatticeHost;
import convex.lattice.LatticeOps;
import convex.lattice.LocalLattice;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.generic.KeyedLattice;

/**
 * Tests for SigningService — encrypted key store kept as a peer's local record.
 */
public class SigningServiceTest {

	/** Shorthand for Strings.create() */
	private static AString s(String v) { return Strings.create(v); }

	/**
	 * Creates a fresh SigningService over an in-memory root.
	 */
	private static SigningService createService() {
		return createService(AKeyPair.generate(), LatticeHost.create().cursor());
	}

	/**
	 * Creates a SigningService with a specific peer key pair and root.
	 */
	private static SigningService createService(AKeyPair peerKP, AForkableCursor<Index<Keyword, ACell>> root) {
		return new SigningService(root, peerKP, Utils::getCurrentTimestampCell);
	}

	/** The peer's signed {@code :local} slot in a root, or null if it has none. */
	@SuppressWarnings("unchecked")
	private static SignedData<AHashMap<Keyword, ACell>> slot(Index<Keyword, ACell> root, AKeyPair peerKP) {
		var local = (AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>>) root.get(LocalLattice.KEY_LOCAL);
		return LocalLattice.getSignedSlot(local, peerKP.getAccountKey());
	}

	/** An application keeping the signing service as its local state, as a persistent service would. */
	private static final class SigningApplication extends ALatticeApplication<Index<Keyword, ACell>> {
		private static final KeyedLattice LATTICE = KeyedLattice.create(LocalLattice.KEY_LOCAL, LocalLattice.LATTICE);
		final SigningService signing;

		SigningApplication(LatticeHost<Index<Keyword, ACell>> host, AKeyPair peerKP) {
			super(host.cursor().fork(),host::persist);
			signing = new SigningService(cursor(), peerKP, Utils::getCurrentTimestampCell, host::persist);
		}

		@Override
		public Index<Keyword, ACell> merge(Index<Keyword, ACell> other) {
			return cursor().updateAndGet(own -> LATTICE.merge(own, other));
		}

		@Override
		protected Index<Keyword, ACell> mergePublication(Index<Keyword, ACell> own, Index<Keyword, ACell> other) {
			return LatticeOps.mergePublication(LATTICE, LatticeContext.EMPTY, own, other);
		}

		@Override
		protected void syncChildren() {
			signing.sync();
		}
	}

	// ===== Local record =====

	@Test
	public void testConstructionCreatesNoState() {
		LatticeHost<Index<Keyword, ACell>> host = LatticeHost.create();
		Index<Keyword, ACell> before = host.cursor().get();

		SigningService svc = createService(AKeyPair.generate(), host.cursor());
		assertTrue(svc.cursor().get().isEmpty());
		assertTrue(svc.listKeys(s("did:key:alice")).isEmpty());
		svc.sync();
		assertSame(before, host.cursor().get(), "An unused service writes and signs nothing");
	}

	@Test
	public void testKeysStayPrivateUntilSync() {
		AKeyPair peerKP = AKeyPair.generate();
		LatticeHost<Index<Keyword, ACell>> host = LatticeHost.create();
		Index<Keyword, ACell> before = host.cursor().get();

		SigningService svc = createService(peerKP, host.cursor());
		svc.createKey(s("did:key:alice"), s("pass"));
		assertSame(before, host.cursor().get(), "Key operations do not publish the record");

		svc.sync();
		SignedData<AHashMap<Keyword, ACell>> signed = slot(host.cursor().get(), peerKP);
		assertTrue(signed.checkSignature());
		assertEquals(peerKP.getAccountKey(), signed.getAccountKey());
		assertEquals(svc.cursor().get(), signed.getValue().get(SigningService.KEY_SIGNING));
	}

	@Test
	public void testKeysSurviveRestartInPersistentApplication() throws IOException {
		AKeyPair peerKP = AKeyPair.generate();
		AString alice = s("did:key:alice");
		Blob message = Blob.wrap("hello".getBytes());
		try (MemoryStore store = new MemoryStore()) {
			AccountKey pk;
			try (LatticeHost<Index<Keyword, ACell>> host = LatticeHost.create(store)) {
				SigningApplication app = new SigningApplication(host, peerKP);
				pk = app.signing.createKey(alice, s("pass"));
				app.signing.persist();
				assertNull(store.getRootData(), "Persisting the working copy retains no root");
				app.sync();
			}

			try (LatticeHost<Index<Keyword, ACell>> host = LatticeHost.open(store)) {
				SigningApplication app = new SigningApplication(host, peerKP);
				assertEquals(List.of(pk), app.signing.listKeys(alice));
				assertTrue(app.signing.sign(alice, pk, s("pass"), message).verify(message, pk));
			}
		}
	}

	@Test
	public void testNewerRestoredRecordIsUsed() {
		AKeyPair peerKP = AKeyPair.generate();
		AtomicLong time = new AtomicLong(20);
		AString alice = s("did:key:alice");

		SigningService backup = new SigningService(LatticeHost.create().cursor(), peerKP,
				() -> CVMLong.create(time.get()));
		AccountKey backedUp = backup.createKey(alice, s("pass"));
		AHashMap<Keyword, ACell> record = backup.cursor().get();

		// This copy's own record, under a secret of its own, is older than the backup
		time.set(10);
		SigningService svc = new SigningService(LatticeHost.create().cursor(), peerKP,
				() -> CVMLong.create(time.get()));
		svc.createKey(alice, s("pass"));

		svc.merge(record);
		assertEquals(List.of(backedUp), svc.listKeys(alice));
		assertNotNull(svc.loadKey(alice, backedUp, s("pass")), "The restored record's secret is used");
	}

	// ===== createKey =====

	@Test
	public void testCreateKeyReturnsPublicKey() {
		SigningService svc = createService();
		AccountKey pk = svc.createKey(s("did:key:alice"), s("secret"));
		assertNotNull(pk);
		assertEquals(32, pk.getBytes().length);
	}

	@Test
	public void testCreateKeyAppearsInListKeys() {
		SigningService svc = createService();
		AccountKey pk = svc.createKey(s("did:key:alice"), s("mypass"));

		List<AccountKey> keys = svc.listKeys(s("did:key:alice"));
		assertEquals(1, keys.size());
		assertEquals(pk, keys.get(0));
	}

	@Test
	public void testCreateMultipleKeysForSameIdentity() {
		SigningService svc = createService();
		AccountKey pk1 = svc.createKey(s("did:key:alice"), s("pass1"));
		AccountKey pk2 = svc.createKey(s("did:key:alice"), s("pass2"));

		assertNotEquals(pk1, pk2, "Different keys should have different public keys");

		List<AccountKey> keys = svc.listKeys(s("did:key:alice"));
		assertEquals(2, keys.size());
		assertTrue(keys.contains(pk1));
		assertTrue(keys.contains(pk2));
	}

	@Test
	public void testCreateKeysForDifferentIdentities() {
		SigningService svc = createService();
		AccountKey pkA = svc.createKey(s("did:key:alice"), s("pass"));
		AccountKey pkB = svc.createKey(s("did:key:bob"), s("pass"));

		// Each identity sees only its own keys
		List<AccountKey> aliceKeys = svc.listKeys(s("did:key:alice"));
		assertEquals(1, aliceKeys.size());
		assertEquals(pkA, aliceKeys.get(0));

		List<AccountKey> bobKeys = svc.listKeys(s("did:key:bob"));
		assertEquals(1, bobKeys.size());
		assertEquals(pkB, bobKeys.get(0));
	}

	@Test
	public void testListKeysUnknownIdentity() {
		SigningService svc = createService();
		List<AccountKey> keys = svc.listKeys(s("did:key:unknown"));
		assertTrue(keys.isEmpty());
	}

	// ===== Encrypted Key Store =====

	@Test
	public void testStoredKeyCanBeLoaded() {
		SigningService svc = createService();
		AccountKey pk = svc.createKey(s("did:key:alice"), s("mypass"));

		byte[] seed = svc.loadKey(s("did:key:alice"), pk, s("mypass"));
		assertNotNull(seed, "Seed should be loadable with correct credentials");
		assertEquals(32, seed.length);
	}

	@Test
	public void testWrongPassphraseCannotLoadKey() {
		SigningService svc = createService();
		AccountKey pk = svc.createKey(s("did:key:alice"), s("correct"));

		// Wrong passphrase → different lookup hash → key not found
		assertNull(svc.loadKey(s("did:key:alice"), pk, s("wrong")),
				"Wrong passphrase should produce different lookup hash");
	}

	@Test
	public void testWrongIdentityCannotLoadKey() {
		SigningService svc = createService();
		AccountKey pk = svc.createKey(s("did:key:alice"), s("pass"));

		// Wrong identity → different lookup hash → key not found
		assertNull(svc.loadKey(s("did:key:bob"), pk, s("pass")),
				"Wrong identity should produce different lookup hash");
	}

	@Test
	public void testLoadedSeedMatchesOriginalKey() {
		SigningService svc = createService();
		AccountKey pk = svc.createKey(s("did:key:alice"), s("pass"));

		byte[] seed = svc.loadKey(s("did:key:alice"), pk, s("pass"));
		AKeyPair recovered = AKeyPair.create(seed);
		assertEquals(pk, recovered.getAccountKey(),
				"Recovered key pair should have same public key");
	}

	// ===== Sync and Reload =====

	@Test
	public void testSyncedKeysReloadFromSlot() {
		AKeyPair peerKP = AKeyPair.generate();
		LatticeHost<Index<Keyword, ACell>> host = LatticeHost.create();

		// Create service, add keys
		SigningService svc1 = createService(peerKP, host.cursor());
		AccountKey pk1 = svc1.createKey(s("did:key:alice"), s("pass1"));
		AccountKey pk2 = svc1.createKey(s("did:key:alice"), s("pass2"));
		svc1.sync();

		// A new instance over the same root starts from the synced record
		SigningService svc2 = createService(peerKP, host.cursor());

		// Keys should survive
		List<AccountKey> keys = svc2.listKeys(s("did:key:alice"));
		assertEquals(2, keys.size());
		assertTrue(keys.contains(pk1));
		assertTrue(keys.contains(pk2));

		// Should be able to load seeds
		byte[] seed1 = svc2.loadKey(s("did:key:alice"), pk1, s("pass1"));
		assertNotNull(seed1);
		assertEquals(pk1, AKeyPair.create(seed1).getAccountKey());
	}

	@Test
	public void testDifferentPeerKeyCannotDecryptSecret() {
		// Peer 1 creates service and keys
		SigningService svc1 = createService();
		AccountKey pk = svc1.createKey(s("did:key:alice"), s("pass"));

		// Peer 2 is given peer 1's record
		SigningService svc2 = createService();
		svc2.merge(svc1.cursor().get());
		assertThrows(RuntimeException.class, () -> svc2.loadKey(s("did:key:alice"), pk, s("pass")),
				"Different peer key should fail to decrypt the encryption secret");
		assertThrows(RuntimeException.class, () -> svc2.createKey(s("did:key:bob"), s("pass")),
				"A secret that cannot be decrypted is never replaced");
	}

	// ===== encryptionSecret Round-Trip =====

	@Test
	public void testEncryptionSecretRoundTrip() {
		SigningService svc = createService();
		byte[] secret = new byte[32];
		new java.security.SecureRandom().nextBytes(secret);

		ACell encrypted = svc.encryptSecret(secret);
		assertNotNull(encrypted);

		byte[] decrypted = svc.decryptSecret((convex.core.data.ABlob) encrypted);
		assertArrayEquals(secret, decrypted);
	}

	// ===== sign() =====

	@Test
	public void testSignAndVerify() {
		SigningService svc = createService();
		AccountKey pk = svc.createKey(s("did:key:alice"), s("pass"));

		Blob message = Blob.wrap("hello world".getBytes());
		ASignature sig = svc.sign(s("did:key:alice"), pk, s("pass"), message);
		assertNotNull(sig);

		assertTrue(sig.verify(message, pk), "Signature should verify with correct public key");
	}

	@Test
	public void testSignWrongPassphrase() {
		SigningService svc = createService();
		AccountKey pk = svc.createKey(s("did:key:alice"), s("correct"));

		Blob message = Blob.wrap("test".getBytes());
		assertNull(svc.sign(s("did:key:alice"), pk, s("wrong"), message),
				"Wrong passphrase should return null");
	}

	@Test
	public void testSignWrongIdentity() {
		SigningService svc = createService();
		AccountKey pk = svc.createKey(s("did:key:alice"), s("pass"));

		Blob message = Blob.wrap("test".getBytes());
		assertNull(svc.sign(s("did:key:bob"), pk, s("pass"), message),
				"Wrong identity should return null");
	}

	// ===== getSelfSignedJWT() =====

	@Test
	public void testGetSelfSignedJWT() {
		SigningService svc = createService();
		AccountKey pk = svc.createKey(s("did:key:alice"), s("pass"));

		AString jwt = svc.getSelfSignedJWT(s("did:key:alice"), pk, s("pass"), null, null, 3600);
		assertNotNull(jwt);

		// Verify the JWT signature
		AMap<AString, ACell> claims = JWT.verifyPublic(jwt);
		assertNotNull(claims, "JWT should verify successfully");
	}

	@Test
	public void testGetSelfSignedJWTSubAndIss() {
		SigningService svc = createService();
		AccountKey pk = svc.createKey(s("did:key:alice"), s("pass"));

		AString jwt = svc.getSelfSignedJWT(s("did:key:alice"), pk, s("pass"), null, null, 3600);
		AMap<AString, ACell> claims = JWT.verifyPublic(jwt);
		assertNotNull(claims);

		// sub and iss should be did:key:<multikey>
		String expectedDID = "did:key:" + Multikey.encodePublicKey(pk);
		assertEquals(expectedDID, RT.ensureString(claims.get(Strings.create("sub"))).toString());
		assertEquals(expectedDID, RT.ensureString(claims.get(Strings.create("iss"))).toString());
	}

	@Test
	public void testGetSelfSignedJWTAudience() {
		SigningService svc = createService();
		AccountKey pk = svc.createKey(s("did:key:alice"), s("pass"));

		AString jwt = svc.getSelfSignedJWT(s("did:key:alice"), pk, s("pass"),
				"https://api.example.com", null, 3600);
		AMap<AString, ACell> claims = JWT.verifyPublic(jwt);
		assertNotNull(claims);

		assertEquals("https://api.example.com",
				RT.ensureString(claims.get(Strings.create("aud"))).toString());
	}

	@Test
	public void testGetSelfSignedJWTExtraClaims() {
		SigningService svc = createService();
		AccountKey pk = svc.createKey(s("did:key:alice"), s("pass"));

		AMap<AString, ACell> extra = Maps.of(
			Strings.create("scope"), Strings.create("read write"),
			Strings.create("nonce"), Strings.create("abc123")
		);

		AString jwt = svc.getSelfSignedJWT(s("did:key:alice"), pk, s("pass"), null, extra, 3600);
		AMap<AString, ACell> claims = JWT.verifyPublic(jwt);
		assertNotNull(claims);

		assertEquals("read write",
				RT.ensureString(claims.get(Strings.create("scope"))).toString());
		assertEquals("abc123",
				RT.ensureString(claims.get(Strings.create("nonce"))).toString());
	}

	@Test
	public void testGetSelfSignedJWTWrongPassphrase() {
		SigningService svc = createService();
		AccountKey pk = svc.createKey(s("did:key:alice"), s("correct"));

		assertNull(svc.getSelfSignedJWT(s("did:key:alice"), pk, s("wrong"), null, null, 3600),
				"Wrong passphrase should return null");
	}

	@Test
	public void testGetSelfSignedJWTExpiry() {
		SigningService svc = createService();
		AccountKey pk = svc.createKey(s("did:key:alice"), s("pass"));

		AString jwt = svc.getSelfSignedJWT(s("did:key:alice"), pk, s("pass"), null, null, 3600);
		AMap<AString, ACell> claims = JWT.verifyPublic(jwt);
		assertNotNull(claims);

		// exp should be in the future
		long exp = Long.parseLong(claims.get(Strings.create("exp")).toString());
		long now = System.currentTimeMillis() / 1000;
		assertTrue(exp > now, "Expiry should be in the future");
		assertTrue(exp <= now + 3600, "Expiry should not exceed lifetime");
	}

	// ===== importKey() =====

	@Test
	public void testImportKeyReturnsCorrectPublicKey() {
		SigningService svc = createService();
		AKeyPair kp = AKeyPair.generate();
		Blob seed = kp.getSeed();

		AccountKey pk = svc.importKey(s("did:key:alice"), seed, s("pass"));
		assertEquals(kp.getAccountKey(), pk, "Imported key should have correct public key");

		List<AccountKey> keys = svc.listKeys(s("did:key:alice"));
		assertEquals(1, keys.size());
		assertEquals(pk, keys.get(0));
	}

	@Test
	public void testImportDuplicateKeyIdempotent() {
		SigningService svc = createService();
		AKeyPair kp = AKeyPair.generate();
		Blob seed = kp.getSeed();

		AccountKey pk1 = svc.importKey(s("did:key:alice"), seed, s("pass"));
		AccountKey pk2 = svc.importKey(s("did:key:alice"), seed, s("pass"));

		assertEquals(pk1, pk2, "Same seed should produce same public key");

		// Should not create duplicate entries in user index
		List<AccountKey> keys = svc.listKeys(s("did:key:alice"));
		assertEquals(1, keys.size());
	}

	// ===== exportKey() =====

	@Test
	public void testExportKeyMatchesImportedSeed() {
		SigningService svc = createService();
		AKeyPair kp = AKeyPair.generate();
		Blob seed = kp.getSeed();

		svc.importKey(s("did:key:alice"), seed, s("pass"));

		ABlob exported = svc.exportKey(s("did:key:alice"), kp.getAccountKey(), s("pass"));
		assertNotNull(exported);
		assertArrayEquals(seed.getBytes(), exported.getBytes(),
				"Exported seed should match imported seed");
	}

	@Test
	public void testExportKeyWrongPassphrase() {
		SigningService svc = createService();
		AccountKey pk = svc.createKey(s("did:key:alice"), s("correct"));

		assertNull(svc.exportKey(s("did:key:alice"), pk, s("wrong")),
				"Wrong passphrase should return null");
	}

	// ===== deleteKey() =====

	@Test
	public void testDeleteKeyRemovedFromListKeys() {
		SigningService svc = createService();
		AccountKey pk = svc.createKey(s("did:key:alice"), s("pass"));
		assertEquals(1, svc.listKeys(s("did:key:alice")).size());

		assertTrue(svc.deleteKey(s("did:key:alice"), pk, s("pass")));

		List<AccountKey> keys = svc.listKeys(s("did:key:alice"));
		assertTrue(keys.isEmpty(), "Deleted key should not appear in listKeys");
		assertFalse(svc.deleteKey(s("did:key:alice"), pk, s("pass")), "A deleted key cannot be deleted again");
	}

	@Test
	public void testDeleteKeyWrongPassphraseKeepsKey() {
		SigningService svc = createService();
		AccountKey pk = svc.createKey(s("did:key:alice"), s("pass"));

		assertFalse(svc.deleteKey(s("did:key:alice"), pk, s("wrong")));

		assertEquals(List.of(pk), svc.listKeys(s("did:key:alice")), "Key should stay listed");
		assertNotNull(svc.loadKey(s("did:key:alice"), pk, s("pass")), "Key should stay usable");
	}

	@Test
	public void testDeleteKeyCannotBeLoaded() {
		SigningService svc = createService();
		AccountKey pk = svc.createKey(s("did:key:alice"), s("pass"));

		svc.deleteKey(s("did:key:alice"), pk, s("pass"));

		assertNull(svc.loadKey(s("did:key:alice"), pk, s("pass")),
				"Deleted key should not be loadable");
	}

	@Test
	public void testDeleteKeyPreservesOtherKeys() {
		SigningService svc = createService();
		AccountKey pk1 = svc.createKey(s("did:key:alice"), s("pass1"));
		AccountKey pk2 = svc.createKey(s("did:key:alice"), s("pass2"));

		svc.deleteKey(s("did:key:alice"), pk1, s("pass1"));

		List<AccountKey> keys = svc.listKeys(s("did:key:alice"));
		assertEquals(1, keys.size());
		assertEquals(pk2, keys.get(0));

		// pk2 should still be loadable
		assertNotNull(svc.loadKey(s("did:key:alice"), pk2, s("pass2")));
	}

	@Test
	public void testDeleteKeyPersistsAcrossRestart() {
		AKeyPair peerKP = AKeyPair.generate();
		LatticeHost<Index<Keyword, ACell>> host = LatticeHost.create();

		SigningService svc1 = createService(peerKP, host.cursor());
		AccountKey pk = svc1.createKey(s("did:key:alice"), s("pass"));
		svc1.sync();
		svc1.deleteKey(s("did:key:alice"), pk, s("pass"));
		svc1.sync();

		// Restart over the same root
		SigningService svc2 = createService(peerKP, host.cursor());

		assertTrue(svc2.listKeys(s("did:key:alice")).isEmpty(),
				"Deletion should persist across restart");
		assertNull(svc2.loadKey(s("did:key:alice"), pk, s("pass")),
				"Deleted key should not be loadable after restart");
	}

	// ===== changePassphrase() =====

	@Test
	public void testChangePassphraseNewWorks() {
		SigningService svc = createService();
		AccountKey pk = svc.createKey(s("did:key:alice"), s("oldpass"));

		svc.changePassphrase(s("did:key:alice"), pk, s("oldpass"), s("newpass"));

		// New passphrase should work
		byte[] seed = svc.loadKey(s("did:key:alice"), pk, s("newpass"));
		assertNotNull(seed, "New passphrase should decrypt the key");
		assertEquals(pk, AKeyPair.create(seed).getAccountKey());
	}

	@Test
	public void testChangePassphraseOldFails() {
		SigningService svc = createService();
		AccountKey pk = svc.createKey(s("did:key:alice"), s("oldpass"));

		svc.changePassphrase(s("did:key:alice"), pk, s("oldpass"), s("newpass"));

		// Old passphrase should no longer work
		assertNull(svc.loadKey(s("did:key:alice"), pk, s("oldpass")),
				"Old passphrase should no longer decrypt the key");
	}

	@Test
	public void testChangePassphraseWrongOldThrows() {
		SigningService svc = createService();
		AccountKey pk = svc.createKey(s("did:key:alice"), s("correct"));
		AHashMap<Keyword, ACell> before = svc.cursor().get();

		assertThrows(IllegalArgumentException.class,
				() -> svc.changePassphrase(s("did:key:alice"), pk, s("wrong"), s("new")),
				"Wrong old passphrase should throw");
		assertSame(before, svc.cursor().get(), "A failed change leaves the record as it was");
	}

	@Test
	public void testChangePassphrasePreservesUserIndex() {
		SigningService svc = createService();
		AccountKey pk = svc.createKey(s("did:key:alice"), s("oldpass"));

		svc.changePassphrase(s("did:key:alice"), pk, s("oldpass"), s("newpass"));

		// Key should still appear in listKeys
		List<AccountKey> keys = svc.listKeys(s("did:key:alice"));
		assertEquals(1, keys.size());
		assertEquals(pk, keys.get(0));
	}

	// ===== Multi-Peer Isolation =====

	@Test
	public void testIndependentServicesIndependentCursors() {
		SigningService svcA = createService();
		SigningService svcB = createService();

		AccountKey pkA = svcA.createKey(s("did:key:alice"), s("pass"));
		AccountKey pkB = svcB.createKey(s("did:key:alice"), s("pass"));

		// Same identity, different services → different keys, each only sees its own
		assertNotEquals(pkA, pkB);
		assertEquals(1, svcA.listKeys(s("did:key:alice")).size());
		assertEquals(pkA, svcA.listKeys(s("did:key:alice")).get(0));
		assertEquals(1, svcB.listKeys(s("did:key:alice")).size());
		assertEquals(pkB, svcB.listKeys(s("did:key:alice")).get(0));

		// Cross-service access fails (different encryptionSecret)
		assertNull(svcA.loadKey(s("did:key:alice"), pkB, s("pass")));
		assertNull(svcB.loadKey(s("did:key:alice"), pkA, s("pass")));
	}

	// ===== Key Rotation (re-wrap encryptionSecret) =====

	@Test
	public void testPeerKeyRotation() {
		AKeyPair oldKP = AKeyPair.generate();
		AKeyPair newKP = AKeyPair.generate();

		// Create service with old peer key, add signing keys
		SigningService oldSvc = createService(oldKP, LatticeHost.create().cursor());
		AccountKey pk1 = oldSvc.createKey(s("did:key:alice"), s("pass1"));
		AccountKey pk2 = oldSvc.createKey(s("did:key:alice"), s("pass2"));

		// Re-wrap: decrypt the encryption secret with the old key, re-encrypt with the new key
		AHashMap<Keyword, ACell> data = oldSvc.cursor().get();
		byte[] rawSecret = oldSvc.decryptSecret((ABlob) data.get(SigningService.KEY_SECRET));
		SigningService newSvc = createService(newKP, LatticeHost.create().cursor());
		ABlob newEncryptedSecret = newSvc.encryptSecret(rawSecret);
		java.util.Arrays.fill(rawSecret, (byte) 0);

		// The new peer key's service takes the record with the re-wrapped secret
		newSvc.merge(data.assoc(SigningService.KEY_SECRET, newEncryptedSecret));

		// All signing keys still accessible
		List<AccountKey> keys = newSvc.listKeys(s("did:key:alice"));
		assertEquals(2, keys.size());
		assertTrue(keys.contains(pk1));
		assertTrue(keys.contains(pk2));

		assertNotNull(newSvc.loadKey(s("did:key:alice"), pk1, s("pass1")));
		assertNotNull(newSvc.loadKey(s("did:key:alice"), pk2, s("pass2")));

		// Verify loaded seed matches original key
		byte[] seed1 = newSvc.loadKey(s("did:key:alice"), pk1, s("pass1"));
		assertEquals(pk1, AKeyPair.create(seed1).getAccountKey());
	}

	// ===== Edge Cases =====

	@Test
	public void testNullConstructorArgs() {
		AKeyPair kp = AKeyPair.generate();
		AForkableCursor<Index<Keyword, ACell>> root = LatticeHost.create().cursor();

		assertThrows(NullPointerException.class, () -> createService(null, root));
		assertThrows(NullPointerException.class, () -> createService(kp, null));
	}

	// ===== Lookup hash keying =====

	@SuppressWarnings("unchecked")
	private static Index<Hash, ABlob> keysIndex(SigningService svc) {
		return (Index<Hash, ABlob>) svc.cursor().get().get(SigningService.KEY_KEYS);
	}

	@Test
	public void testLookupHashIsKeyedToService() {
		AString identity = s("did:key:alice");
		AString pass = s("mypass");
		Blob seed = AKeyPair.generate().getSeed();

		SigningService a = createService();
		SigningService b = createService();
		a.importKey(identity, seed, pass);
		b.importKey(identity, seed, pass);

		// Without the peer-held secret the hash cannot be recomputed, so a copy of the
		// record gives no offline oracle for guessing the passphrase
		assertEquals(1, keysIndex(a).count());
		assertNotEquals(keysIndex(a).entryAt(0).getKey(), keysIndex(b).entryAt(0).getKey());
	}

}
