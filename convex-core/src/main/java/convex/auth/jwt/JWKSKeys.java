package convex.auth.jwt;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.ASequence;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSON;

/**
 * Utility for parsing JWKS (JSON Web Key Set) JSON into RSA public keys.
 *
 * <p>Parses the standard JWKS format used by OAuth providers (Google, Microsoft, Auth0)
 * to extract RSA public keys for RS256 JWT verification.
 *
 * <p>This class does not perform HTTP fetching — callers must supply the raw JSON
 * from the provider's {@code jwks_uri} endpoint.
 */
public class JWKSKeys {

	private static final Base64.Decoder decoder = Base64.getUrlDecoder();

	/**
	 * Parse a JWKS JSON string and return a map of kid to RSAPublicKey.
	 * Only includes keys where kty=RSA and (use=sig or use is absent).
	 *
	 * @param jwksJson Raw JSON from a JWKS endpoint
	 * @return Map of kid to RSAPublicKey, never null (empty if no valid keys)
	 */
	public static Map<String, RSAPublicKey> parseKeys(String jwksJson) {
		Map<String, RSAPublicKey> result = new HashMap<>();
		try {
			AMap<AString,ACell> jwks = RT.ensureMap(JSON.parse(jwksJson));
			if (jwks == null) return result;

			ASequence<ACell> keys = (ASequence<ACell>) RT.ensureSequence(jwks.get(Strings.create("keys")));
			if (keys == null) return result;

			for (long i = 0; i < keys.count(); i++) {
				AMap<AString,ACell> key = RT.ensureMap(keys.get(i));
				if (key == null) continue;

				String kty = str(key, "kty");
				if (!"RSA".equals(kty)) continue;

				String use = str(key, "use");
				if (use != null && !"sig".equals(use)) continue;

				String kid = str(key, "kid");
				String n = str(key, "n");
				String e = str(key, "e");
				if (kid == null || n == null || e == null) continue;

				RSAPublicKey rsaKey = buildRSAPublicKey(n, e);
				if (rsaKey != null) {
					result.put(kid, rsaKey);
				}
			}
		} catch (Exception e) {
			// Return whatever we parsed so far
		}
		return result;
	}

	/**
	 * Build an RSAPublicKey from Base64URL-encoded modulus and exponent.
	 *
	 * @param modulusB64 Base64URL-encoded RSA modulus (n)
	 * @param exponentB64 Base64URL-encoded RSA exponent (e)
	 * @return RSAPublicKey, or null if construction fails
	 */
	public static RSAPublicKey buildRSAPublicKey(String modulusB64, String exponentB64) {
		try {
			BigInteger n = new BigInteger(1, decoder.decode(modulusB64));
			BigInteger e = new BigInteger(1, decoder.decode(exponentB64));
			RSAPublicKeySpec spec = new RSAPublicKeySpec(n, e);
			KeyFactory factory = KeyFactory.getInstance("RSA");
			return (RSAPublicKey) factory.generatePublic(spec);
		} catch (Exception e) {
			return null;
		}
	}

	private static String str(AMap<AString,ACell> map, String key) {
		AString v = RT.ensureString(map.get(Strings.create(key)));
		return v != null ? v.toString() : null;
	}
}
