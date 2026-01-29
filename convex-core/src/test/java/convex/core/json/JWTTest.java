package convex.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Symbols;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;

public class JWTTest {

	@Test public void testClaims() {
		assertEquals("{}",JWT.claims(Maps.empty()).toString());
		assertEquals("{\"1\":\"foo\"}",JWT.claims(Maps.of(1,Symbols.FOO)).toString());
	}
	
	// see: https://www.jwt.io/
	@Test public void testExample() {
		AString secret=Strings.create("a-string-secret-at-least-256-bits-long");
		
		AString head=Strings.create("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
		AString base64Head=JWT.encode(head);
		assertEquals(JWT.HEADER_HS256,base64Head);
		assertEquals("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9",base64Head.toString());
		
		AString claims=Strings.create("{\"sub\":\"1234567890\",\"name\":\"John Doe\",\"admin\":true,\"iat\":1516239022}");
		AString base64Claims=JWT.encode(claims);
		assertEquals("eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiYWRtaW4iOnRydWUsImlhdCI6MTUxNjIzOTAyMn0",base64Claims.toString());

		AString toSign=base64Head.append(".").append(base64Claims);
		AString sig=JWT.signHS256(toSign, secret);
		assertEquals("KMUFsIDTnFmyG3nMiGM6H9FNFUROf3wh7SmqJp-QV30",sig.toString());
	}
	
	// See: https://datatracker.ietf.org/doc/html/rfc7515#appendix-A.1.1
	@Test public void testJWSExample() {
		AString head=Strings.create("{\"typ\":\"JWT\",\r\n \"alg\":\"HS256\"}");
		AString base64Head=JWT.encode(head);
		assertEquals("eyJ0eXAiOiJKV1QiLA0KICJhbGciOiJIUzI1NiJ9",base64Head.toString());

		AString claims=Strings.create("{\"iss\":\"joe\",\r\n \"exp\":1300819380,\r\n \"http://example.com/is_root\":true}");
		AString base64Claims=JWT.encode(claims);
		assertEquals("eyJpc3MiOiJqb2UiLA0KICJleHAiOjEzMDA4MTkzODAsDQogImh0dHA6Ly9leGFtcGxlLmNvbS9pc19yb290Ijp0cnVlfQ",base64Claims.toString());

		AString jwkKey=Strings.create("AyM1SysPpbyDfgZld3umj1qzKObwVMkoqQ-EstJQLr_T-1qS0gZH75aKtMN3Yj0iPS4hcgUuTwjAzZr1Z9CAow");
		byte[] key=JWT.decodeRaw(jwkKey);
		//System.err.println(Strings.wrap(key));
		//System.err.println(Arrays.toString(key));
		
		AString message=base64Head.append(".").append(base64Claims);
		
		AString sig=JWT.signHS256(message, key);
		assertEquals("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk",sig.toString());
		
		AString jwt=message.append(".").append(sig);
		assertTrue(JWT.verifyHS256(jwt, key));
	}

	@Test public void testSignSymmetricRoundTrip() {
		AString secret = Strings.create("a-secret-key-used-for-testing");
		AMap<AString, convex.core.data.ACell> claims = Maps.of(
			"foo", "bar",
			"answer", 42);

		AString jwt = JWT.signSymmetric(claims, secret);

		assertTrue(JWT.verifyHS256(jwt, secret.getBytes()));

		String[] parts = jwt.toString().split("\\.");
		assertEquals(3, parts.length);

		// decode the claims part to ensure it matches what we signed
		AString claimsBase64 = Strings.create(parts[1]);
		String decodedClaims = new String(JWT.decodeRaw(claimsBase64));
		assertEquals(JWT.claims(claims).toString(), decodedClaims);
	}

	@Test public void testSignPublic() {
		AKeyPair kp = AKeyPair.createSeeded(12345L);
		AMap<AString, convex.core.data.ACell> claims = Maps.of(
			"sub", "alice@example.org",
			"scope", "read");

		AString jwt = JWT.signPublic(claims, kp);

		String[] parts = jwt.toString().split("\\.");
		assertEquals(3, parts.length);

		AString headerB64 = Strings.create(parts[0]);
		String headerJson = new String(JWT.decodeRaw(headerB64));
		assertTrue(headerJson.contains("\"alg\":\"EdDSA\""));
		assertTrue(headerJson.contains("\"kid\":\"z"));

		AString payloadB64 = Strings.create(parts[1]);
		AString message = Strings.create(parts[0]).append(".").append(payloadB64);
		ASignature sig = ASignature.fromBlob(Blob.wrap(JWT.decodeRaw(Strings.create(parts[2]))));
		assertTrue(sig.verify(Blob.wrap(message.getBytes()), kp.getAccountKey()));

		String decodedClaims = new String(JWT.decodeRaw(payloadB64));
		assertEquals(JWT.claims(claims).toString(), decodedClaims);
	}

	@Test public void testVerifyPublicRoundTrip() {
		AKeyPair kp = AKeyPair.generate();
		AMap<AString, convex.core.data.ACell> claims = Maps.of(
			"sub", "did:key:z6MkTest",
			"iss", "did:key:z6MkTest",
			"aud", "https://venue.example.com");

		AString jwt = JWT.signPublic(claims, kp);
		AMap<AString, convex.core.data.ACell> verified = JWT.verifyPublic(jwt);

		assertNotNull(verified, "Valid JWT should verify successfully");
		assertEquals("did:key:z6MkTest", verified.get(Strings.create("sub")).toString());
		assertEquals("did:key:z6MkTest", verified.get(Strings.create("iss")).toString());
		assertEquals("https://venue.example.com", verified.get(Strings.create("aud")).toString());
	}

	@Test public void testVerifyPublicRejectsTampered() {
		AKeyPair kp = AKeyPair.generate();
		AMap<AString, convex.core.data.ACell> claims = Maps.of("sub", "alice");

		AString jwt = JWT.signPublic(claims, kp);

		// Tamper with the claims (change a character in the middle)
		String s = jwt.toString();
		int dot1 = s.indexOf('.');
		int dot2 = s.indexOf('.', dot1 + 1);
		String tampered = s.substring(0, dot1 + 2) + "X" + s.substring(dot1 + 3);
		assertNull(JWT.verifyPublic(Strings.create(tampered)), "Tampered JWT should fail verification");
	}

	@Test public void testVerifyPublicRejectsWrongKey() {
		AKeyPair kp1 = AKeyPair.createSeeded(1L);
		AKeyPair kp2 = AKeyPair.createSeeded(2L);
		AMap<AString, convex.core.data.ACell> claims = Maps.of("sub", "alice");

		// Sign with kp1
		AString jwt = JWT.signPublic(claims, kp1);

		// Verify should succeed with correct key embedded in kid
		assertNotNull(JWT.verifyPublic(jwt));

		// Manually swap the header to use kp2's kid but keep kp1's signature
		// This should fail because signature won't match the new kid's public key
		String s = jwt.toString();
		int dot1 = s.indexOf('.');
		// Replace header with one containing kp2's kid
		AString kid2 = convex.core.crypto.util.Multikey.encodePublicKey(kp2.getAccountKey());
		AMap<AString, convex.core.data.ACell> fakeHeader = Maps.of(
			"alg", "EdDSA", "typ", "JWT", "kid", kid2);
		String fakeHeaderB64 = JWT.encode(Strings.create(convex.core.util.JSON.toString(fakeHeader))).toString();
		String forgedJwt = fakeHeaderB64 + s.substring(dot1);
		assertNull(JWT.verifyPublic(Strings.create(forgedJwt)), "JWT with wrong key should fail");
	}

	@Test public void testVerifyPublicRejectsGarbage() {
		assertNull(JWT.verifyPublic(Strings.create("not-a-jwt")));
		assertNull(JWT.verifyPublic(Strings.create("a.b.c")));
		assertNull(JWT.verifyPublic(Strings.create("")));
	}
}
