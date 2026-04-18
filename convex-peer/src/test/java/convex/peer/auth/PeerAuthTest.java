package convex.peer.auth;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.auth.did.DID;
import convex.auth.jwt.JWT;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Maps;
import convex.core.data.Strings;

/**
 * Tests for PeerAuth — bearer token verification and peer token issuance.
 */
public class PeerAuthTest {

	private static final AKeyPair PEER_KP = AKeyPair.generate();
	private static final PeerAuth AUTH = new PeerAuth(PEER_KP);

	// ===== Self-Issued JWT Verification =====

	@Test
	public void testSelfIssuedJWTValid() {
		AKeyPair clientKP = AKeyPair.generate();
		AString jwt = createSelfIssuedJWT(clientKP, 300);

		AString identity = AUTH.verifyBearerToken(jwt);
		assertNotNull(identity, "Valid self-issued JWT should authenticate");

		AString expectedDID = DID.forKey(clientKP.getAccountKey());
		assertEquals(expectedDID, identity);
	}

	@Test
	public void testSelfIssuedJWTExpired() {
		AKeyPair clientKP = AKeyPair.generate();
		AString jwt = createSelfIssuedJWT(clientKP, -60); // expired 60s ago

		assertNull(AUTH.verifyBearerToken(jwt), "Expired self-issued JWT should be rejected");
	}

	@Test
	public void testSelfIssuedJWTTampered() {
		AKeyPair clientKP = AKeyPair.generate();
		AString jwt = createSelfIssuedJWT(clientKP, 300);

		// Tamper by flipping a character in the signature section.
		// Avoid the very last character: base64url padding bits mean some
		// substitutions don't change the decoded bytes.
		String s = jwt.toString();
		int idx = s.lastIndexOf('.') + 2; // second char of signature section
		char c = s.charAt(idx);
		char replaced = (c == 'A') ? 'B' : 'A';
		AString tampered = Strings.create(s.substring(0, idx) + replaced + s.substring(idx + 1));

		assertNull(AUTH.verifyBearerToken(tampered), "Tampered JWT should be rejected");
	}

	@Test
	public void testSelfIssuedJWTKidMismatch() {
		// Sign with one key but set kid to a different key
		AKeyPair signerKP = AKeyPair.generate();
		AKeyPair otherKP = AKeyPair.generate();

		AString correctDID = DID.forKey(otherKP.getAccountKey());

		long now = System.currentTimeMillis() / 1000;
		AMap<AString, ACell> claims = Maps.of(
			JWT.SUB, correctDID,
			JWT.ISS, correctDID,
			JWT.IAT, now,
			JWT.EXP, now + 300
		);

		// Sign with signerKP but kid header will be set by signPublic to signerKP's key
		// so sub won't match kid → rejection
		AString jwt = JWT.signPublic(claims, signerKP);
		assertNull(AUTH.verifyBearerToken(jwt),
				"JWT where sub doesn't match signing key should be rejected");
	}

	// ===== Peer-Signed JWT Verification =====

	@Test
	public void testPeerSignedJWTValid() {
		AString identity = Strings.create("did:web:peer.example.com:oauth:google:12345");
		AString jwt = AUTH.issuePeerToken(identity, 3600);

		AString result = AUTH.verifyBearerToken(jwt);
		assertNotNull(result, "Valid peer-signed JWT should authenticate");
		assertEquals(identity.toString(), result.toString());
	}

	@Test
	public void testPeerSignedJWTWrongPeerKey() {
		// Issue token with this peer
		AString identity = Strings.create("did:web:peer.example.com:oauth:google:12345");
		AString jwt = AUTH.issuePeerToken(identity, 3600);

		// Different peer should reject it
		AKeyPair otherPeerKP = AKeyPair.generate();
		PeerAuth otherAuth = new PeerAuth(otherPeerKP);

		assertNull(otherAuth.verifyBearerToken(jwt),
				"JWT signed by different peer should be rejected");
	}

	@Test
	public void testPeerSignedJWTExpired() {
		AString identity = Strings.create("did:web:peer.example.com:oauth:google:12345");
		// Issue with negative lifetime → already expired
		AString jwt = AUTH.issuePeerToken(identity, -60);

		assertNull(AUTH.verifyBearerToken(jwt), "Expired peer-signed JWT should be rejected");
	}

	// ===== Edge Cases =====

	@Test
	public void testNullToken() {
		assertNull(AUTH.verifyBearerToken(null));
	}

	@Test
	public void testGarbageToken() {
		assertNull(AUTH.verifyBearerToken(Strings.create("not.a.jwt")));
		assertNull(AUTH.verifyBearerToken(Strings.create("")));
		assertNull(AUTH.verifyBearerToken(Strings.create("abc")));
	}

	@Test
	public void testNullConstructorArg() {
		assertThrows(IllegalArgumentException.class, () -> new PeerAuth(null));
	}

	@Test
	public void testIssuePeerTokenClaims() {
		AString identity = Strings.create("did:web:peer.example.com:oauth:apple:001135");
		AString jwt = AUTH.issuePeerToken(identity, 7200);

		// Verify and check claims
		AMap<AString, ACell> claims = JWT.verifyPublic(jwt, PEER_KP.getAccountKey());
		assertNotNull(claims);

		assertEquals(identity.toString(),
				claims.get(JWT.SUB).toString());

		// exp should be in the future
		long exp = Long.parseLong(claims.get(JWT.EXP).toString());
		long now = System.currentTimeMillis() / 1000;
		assertTrue(exp > now);
		assertTrue(exp <= now + 7200);
	}

	@Test
	public void testGetPeerKey() {
		assertEquals(PEER_KP.getAccountKey(), AUTH.getPeerKey());
	}

	// ===== Audience Checking =====

	@Test
	public void testSelfIssuedJWTWithCorrectAudience() {
		PeerAuth audAuth = PeerAuth.createWithDIDAudience(PEER_KP);
		AKeyPair clientKP = AKeyPair.generate();
		AString jwt = createSelfIssuedJWT(clientKP, 300, audAuth.getExpectedAudience());

		AString identity = audAuth.verifyBearerToken(jwt);
		assertNotNull(identity, "Self-issued JWT with correct aud should authenticate");
	}

	@Test
	public void testSelfIssuedJWTWithWrongAudience() {
		PeerAuth audAuth = PeerAuth.createWithDIDAudience(PEER_KP);
		AKeyPair clientKP = AKeyPair.generate();
		AString jwt = createSelfIssuedJWT(clientKP, 300, "did:key:zWRONGAUDIENCE");

		assertNull(audAuth.verifyBearerToken(jwt),
				"Self-issued JWT with wrong aud should be rejected");
	}

	@Test
	public void testSelfIssuedJWTMissingAudienceWhenRequired() {
		PeerAuth audAuth = PeerAuth.createWithDIDAudience(PEER_KP);
		AKeyPair clientKP = AKeyPair.generate();
		// No aud claim at all
		AString jwt = createSelfIssuedJWT(clientKP, 300);

		assertNull(audAuth.verifyBearerToken(jwt),
				"Self-issued JWT without aud should be rejected when audience is required");
	}

	@Test
	public void testSelfIssuedJWTAudienceNotRequiredAcceptsNoAud() {
		// Default AUTH has no expectedAudience
		AKeyPair clientKP = AKeyPair.generate();
		AString jwt = createSelfIssuedJWT(clientKP, 300);

		assertNotNull(AUTH.verifyBearerToken(jwt),
				"Self-issued JWT without aud should be accepted when audience is not required");
	}

	@Test
	public void testPeerSignedJWTWithCorrectAudience() {
		PeerAuth audAuth = PeerAuth.createWithDIDAudience(PEER_KP);
		AString identity = Strings.create("did:web:example.com:user:42");
		AString jwt = audAuth.issuePeerToken(identity, 3600);

		AString result = audAuth.verifyBearerToken(jwt);
		assertNotNull(result, "Peer-signed JWT with correct aud should authenticate");
		assertEquals(identity, result);
	}

	@Test
	public void testPeerSignedJWTWithWrongAudience() {
		// Issue token from a peer without aud checking
		AString identity = Strings.create("did:web:example.com:user:42");
		AString jwt = AUTH.issuePeerToken(identity, 3600);

		// Verify on a peer that requires aud — token has no aud claim
		PeerAuth audAuth = PeerAuth.createWithDIDAudience(PEER_KP);
		assertNull(audAuth.verifyBearerToken(jwt),
				"Peer-signed JWT without aud should be rejected when audience is required");
	}

	@Test
	public void testBadlySignedJWTWithCorrectAudience() {
		// Attacker creates a JWT with the correct aud but signs with wrong key
		PeerAuth audAuth = PeerAuth.createWithDIDAudience(PEER_KP);
		AKeyPair attackerKP = AKeyPair.generate();

		// Craft claims that look like a peer-signed token with correct aud
		long now = System.currentTimeMillis() / 1000;
		AMap<AString, ACell> claims = Maps.of(
			JWT.SUB, "did:web:attacker.com:evil",
			JWT.ISS, DID.forKey(attackerKP.getAccountKey()),
			JWT.AUD, audAuth.getExpectedAudience(),
			JWT.IAT, now,
			JWT.EXP, now + 3600
		);
		AString jwt = JWT.signPublic(claims, attackerKP);

		assertNull(audAuth.verifyBearerToken(jwt),
				"JWT signed by attacker should be rejected even with correct aud");
	}

	@Test
	public void testCreateWithDIDAudience() {
		PeerAuth audAuth = PeerAuth.createWithDIDAudience(PEER_KP);
		assertNotNull(audAuth.getExpectedAudience());
		assertTrue(audAuth.getExpectedAudience().toString().startsWith("did:key:"));
		assertEquals(PEER_KP.getAccountKey(), audAuth.getPeerKey());
	}

	// ===== Helpers =====

	private static AString createSelfIssuedJWT(AKeyPair kp, long lifetimeSeconds) {
		return createSelfIssuedJWT(kp, lifetimeSeconds, (AString) null);
	}

	private static AString createSelfIssuedJWT(AKeyPair kp, long lifetimeSeconds, String audience) {
		return createSelfIssuedJWT(kp, lifetimeSeconds, audience != null ? Strings.create(audience) : null);
	}

	private static AString createSelfIssuedJWT(AKeyPair kp, long lifetimeSeconds, AString audience) {
		AccountKey pk = kp.getAccountKey();
		AString didKey = DID.forKey(pk);

		long now = System.currentTimeMillis() / 1000;
		AMap<AString, ACell> claims = Maps.of(
			JWT.SUB, didKey,
			JWT.ISS, didKey,
			JWT.IAT, now,
			JWT.EXP, now + lifetimeSeconds
		);

		if (audience != null) {
			claims = claims.assoc(JWT.AUD, audience);
		}

		return JWT.signPublic(claims, kp);
	}
}
