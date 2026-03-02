package convex.peer.signing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

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
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.lattice.cursor.ACursor;
import convex.lattice.cursor.Root;

/**
 * Tests for SigningService — encrypted key store operating on a cursor.
 */
public class SigningServiceTest {

	/** Shorthand for Strings.create() */
	private static AString s(String v) { return Strings.create(v); }

	/**
	 * Creates a fresh SigningService backed by an in-memory Root cursor.
	 */
	private static SigningService createService() {
		AKeyPair peerKP = AKeyPair.generate();
		ACursor<ACell> cursor = new Root<>((ACell) null);
		SigningService svc = new SigningService(peerKP, cursor);
		svc.init();
		return svc;
	}

	/**
	 * Creates a SigningService with a specific peer key pair and cursor.
	 */
	private static SigningService createService(AKeyPair peerKP, ACursor<ACell> cursor) {
		SigningService svc = new SigningService(peerKP, cursor);
		svc.init();
		return svc;
	}

	// ===== Initialisation =====

	@Test
	public void testInitCreatesStructure() {
		AKeyPair peerKP = AKeyPair.generate();
		Root<ACell> cursor = new Root<>((ACell) null);

		SigningService svc = new SigningService(peerKP, cursor);
		assertNull(cursor.get(), "Cursor should be null before init");

		svc.init();
		assertNotNull(cursor.get(), "Cursor should have data after init");
	}

	@Test
	public void testInitIdempotent() {
		AKeyPair peerKP = AKeyPair.generate();
		Root<ACell> cursor = new Root<>((ACell) null);

		SigningService svc = new SigningService(peerKP, cursor);
		svc.init();
		ACell first = cursor.get();

		// Second init should load existing, not overwrite
		SigningService svc2 = new SigningService(peerKP, cursor);
		svc2.init();
		ACell second = cursor.get();

		assertEquals(first, second, "Re-init should preserve existing data");
	}

	@Test
	public void testUninitialised() {
		AKeyPair peerKP = AKeyPair.generate();
		Root<ACell> cursor = new Root<>((ACell) null);
		SigningService svc = new SigningService(peerKP, cursor);

		// Operations before init should fail
		assertThrows(IllegalStateException.class, () -> svc.createKey(s("did:key:test"), s("pass")));
		assertThrows(IllegalStateException.class, () -> svc.listKeys(s("did:key:test")));
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

	// ===== Persistence via Cursor =====

	@Test
	public void testPersistAndReloadViaCursor() {
		AKeyPair peerKP = AKeyPair.generate();
		Root<ACell> cursor = new Root<>((ACell) null);

		// Create service, add keys
		SigningService svc1 = createService(peerKP, cursor);
		AccountKey pk1 = svc1.createKey(s("did:key:alice"), s("pass1"));
		AccountKey pk2 = svc1.createKey(s("did:key:alice"), s("pass2"));

		// Simulate restart: new service instance, same cursor state
		// In production, cursor state would be persisted/loaded by the server layer
		ACell savedState = cursor.get();
		Root<ACell> cursor2 = Root.create(savedState);

		SigningService svc2 = createService(peerKP, cursor2);

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
		AKeyPair peerKP1 = AKeyPair.generate();
		Root<ACell> cursor = new Root<>((ACell) null);

		// Peer 1 creates service and keys
		SigningService svc1 = createService(peerKP1, cursor);
		svc1.createKey(s("did:key:alice"), s("pass"));

		// Peer 2 tries to load with same cursor data but different peer key
		AKeyPair peerKP2 = AKeyPair.generate();
		ACell savedState = cursor.get();
		Root<ACell> cursor2 = Root.create(savedState);

		SigningService svc2 = new SigningService(peerKP2, cursor2);
		assertThrows(RuntimeException.class, svc2::init,
				"Different peer key should fail to decrypt encryptionSecret");
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

		svc.deleteKey(s("did:key:alice"), pk, s("pass"));

		List<AccountKey> keys = svc.listKeys(s("did:key:alice"));
		assertTrue(keys.isEmpty(), "Deleted key should not appear in listKeys");
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
		Root<ACell> cursor = new Root<>((ACell) null);

		SigningService svc1 = createService(peerKP, cursor);
		AccountKey pk = svc1.createKey(s("did:key:alice"), s("pass"));
		svc1.deleteKey(s("did:key:alice"), pk, s("pass"));

		// Restart with same cursor state
		ACell savedState = cursor.get();
		Root<ACell> cursor2 = Root.create(savedState);
		SigningService svc2 = createService(peerKP, cursor2);

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

		assertThrows(IllegalArgumentException.class,
				() -> svc.changePassphrase(s("did:key:alice"), pk, s("wrong"), s("new")),
				"Wrong old passphrase should throw");
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

	@SuppressWarnings("unchecked")
	@Test
	public void testPeerKeyRotation() {
		AKeyPair oldKP = AKeyPair.generate();
		AKeyPair newKP = AKeyPair.generate();
		Root<ACell> cursor = new Root<>((ACell) null);

		// Create service with old peer key, add signing keys
		SigningService oldSvc = createService(oldKP, cursor);
		AccountKey pk1 = oldSvc.createKey(s("did:key:alice"), s("pass1"));
		AccountKey pk2 = oldSvc.createKey(s("did:key:alice"), s("pass2"));

		// Re-wrap: decrypt encryptionSecret with old key, re-encrypt with new key
		AHashMap<Keyword, ACell> data = (AHashMap<Keyword, ACell>) cursor.get();
		ABlob oldEncryptedSecret = (ABlob) data.get(SigningService.KEY_SECRET);
		byte[] rawSecret = oldSvc.decryptSecret(oldEncryptedSecret);

		SigningService tempSvc = new SigningService(newKP, new Root<>((ACell) null));
		ABlob newEncryptedSecret = tempSvc.encryptSecret(rawSecret);
		java.util.Arrays.fill(rawSecret, (byte) 0);

		// Update cursor with re-wrapped secret
		data = data.assoc(SigningService.KEY_SECRET, newEncryptedSecret);
		Root<ACell> newCursor = Root.create(data);

		// New service with new peer key should load successfully
		SigningService newSvc = createService(newKP, newCursor);

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
		Root<ACell> cursor = new Root<>((ACell) null);

		assertThrows(IllegalArgumentException.class, () -> new SigningService(null, cursor));
		assertThrows(IllegalArgumentException.class, () -> new SigningService(kp, null));
	}
}
