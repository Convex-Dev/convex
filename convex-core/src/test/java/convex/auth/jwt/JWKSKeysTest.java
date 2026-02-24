package convex.auth.jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;

public class JWKSKeysTest {

	private KeyPair generateRSAKeyPair() throws Exception {
		KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
		gen.initialize(2048);
		return gen.generateKeyPair();
	}

	@Test public void testParseGoogleFormat() throws Exception {
		KeyPair kp = generateRSAKeyPair();
		RSAPublicKey pub = (RSAPublicKey) kp.getPublic();
		Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();

		String n = enc.encodeToString(pub.getModulus().toByteArray());
		String e = enc.encodeToString(pub.getPublicExponent().toByteArray());

		String jwksJson = "{\"keys\":[" +
			"{\"kty\":\"RSA\",\"kid\":\"key-1\",\"use\":\"sig\",\"alg\":\"RS256\"," +
			"\"n\":\"" + n + "\",\"e\":\"" + e + "\"}," +
			"{\"kty\":\"RSA\",\"kid\":\"key-2\",\"use\":\"sig\",\"alg\":\"RS256\"," +
			"\"n\":\"" + n + "\",\"e\":\"" + e + "\"}" +
			"]}";

		Map<String, RSAPublicKey> keys = JWKSKeys.parseKeys(jwksJson);
		assertEquals(2, keys.size());
		assertNotNull(keys.get("key-1"));
		assertNotNull(keys.get("key-2"));
	}

	@Test public void testEmptyKeys() {
		assertEquals(0, JWKSKeys.parseKeys("{\"keys\":[]}").size());
		assertEquals(0, JWKSKeys.parseKeys("{}").size());
		assertEquals(0, JWKSKeys.parseKeys("not json").size());
	}

	@Test public void testFiltersNonRSA() throws Exception {
		// EC key should be skipped
		String jwksJson = "{\"keys\":[" +
			"{\"kty\":\"EC\",\"kid\":\"ec-key\",\"use\":\"sig\"," +
			"\"n\":\"abc\",\"e\":\"def\"}" +
			"]}";

		Map<String, RSAPublicKey> keys = JWKSKeys.parseKeys(jwksJson);
		assertEquals(0, keys.size());
	}

	@Test public void testFiltersEncryptionKeys() throws Exception {
		KeyPair kp = generateRSAKeyPair();
		RSAPublicKey pub = (RSAPublicKey) kp.getPublic();
		Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();

		String n = enc.encodeToString(pub.getModulus().toByteArray());
		String e = enc.encodeToString(pub.getPublicExponent().toByteArray());

		// use=enc should be skipped
		String jwksJson = "{\"keys\":[" +
			"{\"kty\":\"RSA\",\"kid\":\"enc-key\",\"use\":\"enc\"," +
			"\"n\":\"" + n + "\",\"e\":\"" + e + "\"}" +
			"]}";

		Map<String, RSAPublicKey> keys = JWKSKeys.parseKeys(jwksJson);
		assertEquals(0, keys.size());
	}

	@Test public void testRoundTrip() throws Exception {
		KeyPair kp = generateRSAKeyPair();
		RSAPublicKey pub = (RSAPublicKey) kp.getPublic();
		Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();

		String n = enc.encodeToString(pub.getModulus().toByteArray());
		String e = enc.encodeToString(pub.getPublicExponent().toByteArray());

		String jwksJson = "{\"keys\":[" +
			"{\"kty\":\"RSA\",\"kid\":\"round-trip-key\",\"use\":\"sig\"," +
			"\"n\":\"" + n + "\",\"e\":\"" + e + "\"}" +
			"]}";

		// Parse JWKS
		Map<String, RSAPublicKey> keys = JWKSKeys.parseKeys(jwksJson);
		RSAPublicKey parsedKey = keys.get("round-trip-key");
		assertNotNull(parsedKey);

		// Sign a JWT with the private key
		AMap<AString, ACell> claims = Maps.of("sub", "test-user", "iss", "test");
		String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"round-trip-key\"}";
		String headerB64 = enc.encodeToString(header.getBytes());
		String claimsJson = convex.core.util.JSON.toString(claims);
		String claimsB64 = enc.encodeToString(claimsJson.getBytes());
		String signingInput = headerB64 + "." + claimsB64;

		Signature sig = Signature.getInstance("SHA256withRSA");
		sig.initSign(kp.getPrivate());
		sig.update(signingInput.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		byte[] sigBytes = sig.sign();
		String sigB64 = enc.encodeToString(sigBytes);
		AString jwtString = Strings.create(signingInput + "." + sigB64);

		// Verify using the parsed key
		AMap<AString, ACell> verified = JWT.verifyRS256(jwtString, parsedKey);
		assertNotNull(verified);
		assertEquals("test-user", verified.get(Strings.create("sub")).toString());
	}

	@Test public void testBuildRSAPublicKey() throws Exception {
		KeyPair kp = generateRSAKeyPair();
		RSAPublicKey original = (RSAPublicKey) kp.getPublic();
		Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();

		String n = enc.encodeToString(original.getModulus().toByteArray());
		String e = enc.encodeToString(original.getPublicExponent().toByteArray());

		RSAPublicKey rebuilt = JWKSKeys.buildRSAPublicKey(n, e);
		assertNotNull(rebuilt);
		assertEquals(original.getModulus(), rebuilt.getModulus());
		assertEquals(original.getPublicExponent(), rebuilt.getPublicExponent());
	}

	@Test public void testBuildRSAPublicKeyInvalid() {
		assertNull(JWKSKeys.buildRSAPublicKey("not-valid-base64url!", "also-bad!"));
	}
}
