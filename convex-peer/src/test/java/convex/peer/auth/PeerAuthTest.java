package convex.peer.auth;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.util.Multikey;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.json.JWT;

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

		AString expectedDID = Strings.create("did:key:").append(Multikey.encodePublicKey(clientKP.getAccountKey()));
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

		// Tamper by changing last character of the JWT
		String s = jwt.toString();
		char last = s.charAt(s.length() - 1);
		char replaced = (last == 'A') ? 'B' : 'A';
		AString tampered = Strings.create(s.substring(0, s.length() - 1) + replaced);

		assertNull(AUTH.verifyBearerToken(tampered), "Tampered JWT should be rejected");
	}

	@Test
	public void testSelfIssuedJWTKidMismatch() {
		// Sign with one key but set kid to a different key
		AKeyPair signerKP = AKeyPair.generate();
		AKeyPair otherKP = AKeyPair.generate();

		AString wrongKid = Multikey.encodePublicKey(otherKP.getAccountKey());
		AString correctDID = Strings.create("did:key:").append(wrongKid);

		long now = System.currentTimeMillis() / 1000;
		AMap<AString, ACell> claims = Maps.of(
			Strings.create("sub"), correctDID,
			Strings.create("iss"), correctDID,
			Strings.create("iat"), CVMLong.create(now),
			Strings.create("exp"), CVMLong.create(now + 300)
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
				claims.get(Strings.create("sub")).toString());

		// exp should be in the future
		long exp = Long.parseLong(claims.get(Strings.create("exp")).toString());
		long now = System.currentTimeMillis() / 1000;
		assertTrue(exp > now);
		assertTrue(exp <= now + 7200);
	}

	@Test
	public void testGetPeerKey() {
		assertEquals(PEER_KP.getAccountKey(), AUTH.getPeerKey());
	}

	// ===== Helpers =====

	private static AString createSelfIssuedJWT(AKeyPair kp, long lifetimeSeconds) {
		AccountKey pk = kp.getAccountKey();
		AString didKey = Strings.create("did:key:").append(Multikey.encodePublicKey(pk));

		long now = System.currentTimeMillis() / 1000;
		AMap<AString, ACell> claims = Maps.of(
			Strings.create("sub"), didKey,
			Strings.create("iss"), didKey,
			Strings.create("iat"), CVMLong.create(now),
			Strings.create("exp"), CVMLong.create(now + lifetimeSeconds)
		);

		return JWT.signPublic(claims, kp);
	}
}
