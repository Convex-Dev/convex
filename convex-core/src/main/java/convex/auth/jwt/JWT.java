package convex.auth.jwt;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.crypto.util.Multikey;
import convex.core.data.ABlob;
import convex.core.data.ABlobLike;
import convex.core.data.AccountKey;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.util.BlobBuilder;
import convex.core.lang.RT;
import convex.core.util.JSON;

/**
 * JSON Web Token (JWT) utilities.
 *
 * <p>Supports both static convenience methods for signing/verification and an
 * instance-based representation that parses a JWT once and caches the decoded
 * header, claims, and signature bytes for efficient repeated access.
 *
 * <h2>Supported Algorithms</h2>
 * <ul>
 *   <li><b>HS256</b> — HMAC-SHA256 symmetric signing/verification</li>
 *   <li><b>EdDSA</b> — Ed25519 public key signing/verification (Convex native)</li>
 *   <li><b>RS256</b> — RSA-SHA256 verification (for external OAuth provider tokens)</li>
 * </ul>
 */
public class JWT {

	static Base64.Decoder decoder=Base64.getUrlDecoder();

	// JWT encoding should be Base64URL without padding
	static Base64.Encoder encoder=Base64.getUrlEncoder().withoutPadding();

	static AString HEADER_HS256 = encode(Strings.intern("{\"alg\":\"HS256\",\"typ\":\"JWT\"}"));
	static final AMap<AString,ACell> HEADER_EDDSA_BASE = Maps.of(
		Strings.create("alg"), Strings.create("EdDSA"),
		Strings.create("typ"), Strings.create("JWT"));

	// Common claim keys
	private static final AString ALG = Strings.create("alg");
	private static final AString KID = Strings.create("kid");
	private static final AString EXP = Strings.create("exp");
	private static final AString ISS = Strings.create("iss");
	private static final AString AUD = Strings.create("aud");

	// ========== Instance fields (parsed and cached) ==========

	private final AString raw;
	private final AMap<AString,ACell> header;
	private final AMap<AString,ACell> claims;
	private final byte[] signatureBytes;
	private final String signingInput;

	private JWT(AString raw, AMap<AString,ACell> header,
				AMap<AString,ACell> claims, byte[] signatureBytes, String signingInput) {
		this.raw = raw;
		this.header = header;
		this.claims = claims;
		this.signatureBytes = signatureBytes;
		this.signingInput = signingInput;
	}

	/**
	 * Parse a JWT string into an instance with cached header, claims, and signature.
	 * Returns null if the JWT is malformed.
	 *
	 * @param jwt The encoded JWT string
	 * @return Parsed JWT instance, or null if malformed
	 */
	public static JWT parse(AString jwt) {
		try {
			String s = jwt.toString();
			int dot1 = s.indexOf('.');
			if (dot1 < 0) return null;
			int dot2 = s.indexOf('.', dot1 + 1);
			if (dot2 < 0) return null;

			String headerB64 = s.substring(0, dot1);
			AMap<AString,ACell> header = RT.ensureMap(JSON.parse(Strings.wrap(decoder.decode(headerB64))));
			if (header == null) return null;

			String claimsB64 = s.substring(dot1 + 1, dot2);
			AMap<AString,ACell> claims = RT.ensureMap(JSON.parse(Strings.wrap(decoder.decode(claimsB64))));
			if (claims == null) return null;

			String sigB64 = s.substring(dot2 + 1);
			byte[] sigBytes = decoder.decode(sigB64);

			String signingInput = s.substring(0, dot2);

			return new JWT(jwt, header, claims, sigBytes, signingInput);
		} catch (Exception e) {
			return null;
		}
	}

	// ========== Instance accessors ==========

	/** Get the decoded header map */
	public AMap<AString,ACell> getHeader() { return header; }

	/** Get the decoded claims map */
	public AMap<AString,ACell> getClaims() { return claims; }

	/** Get the original encoded JWT string */
	public AString getRaw() { return raw; }

	/** Get the algorithm from the header (e.g. "EdDSA", "RS256", "HS256") */
	public String getAlgorithm() {
		AString alg = RT.ensureString(header.get(ALG));
		return alg != null ? alg.toString() : null;
	}

	/** Get the key ID from the header, or null if not present */
	public String getKeyID() {
		AString kid = RT.ensureString(header.get(KID));
		return kid != null ? kid.toString() : null;
	}

	// ========== Instance verification methods ==========

	/**
	 * Verify this JWT as a self-issued EdDSA token.
	 * Extracts the public key from the {@code kid} header (multikey format).
	 *
	 * @return true if signature is valid
	 */
	public boolean verifyEdDSA() {
		try {
			if (!"EdDSA".equals(getAlgorithm())) return false;
			String kid = getKeyID();
			if (kid == null) return false;
			AccountKey publicKey = Multikey.decodePublicKey(kid);
			if (publicKey == null) return false;
			return verifyEdDSA(publicKey);
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Verify this JWT as an EdDSA token against a specific trusted public key.
	 *
	 * @param trustedKey The Ed25519 public key to verify against
	 * @return true if signature is valid
	 */
	public boolean verifyEdDSA(AccountKey trustedKey) {
		try {
			if (!"EdDSA".equals(getAlgorithm())) return false;
			ASignature sig = ASignature.fromBlob(Blob.wrap(signatureBytes));
			return sig.verify(Blob.wrap(signingInput.getBytes(StandardCharsets.UTF_8)), trustedKey);
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Verify this JWT as an RS256 (RSA-SHA256) token.
	 * Used for validating external OAuth provider tokens (Google, Microsoft, etc.).
	 *
	 * @param publicKey RSA public key to verify against
	 * @return true if signature is valid
	 */
	public boolean verifyRS256(RSAPublicKey publicKey) {
		try {
			if (!"RS256".equals(getAlgorithm())) return false;
			Signature verifier = Signature.getInstance("SHA256withRSA");
			verifier.initVerify(publicKey);
			verifier.update(signingInput.getBytes(StandardCharsets.UTF_8));
			return verifier.verify(signatureBytes);
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Verify this JWT as an HS256 (HMAC-SHA256) token.
	 *
	 * @param secret Shared secret key
	 * @return true if signature is valid
	 */
	public boolean verifyHS256(byte[] secret) {
		try {
			if (!"HS256".equals(getAlgorithm())) return false;
			AString expected = JWT.signHS256(Strings.create(signingInput), secret);
			return expected.equals(Strings.wrap(encoder.encode(signatureBytes)));
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Validate standard JWT claims: exp, iss, aud.
	 *
	 * @param expectedIssuer Expected issuer string, or null to skip issuer check
	 * @param expectedAudience Expected audience (e.g. OAuth client ID), or null to skip
	 * @return true if all checked claims are valid
	 */
	public boolean validateClaims(String expectedIssuer, String expectedAudience) {
		if (claims == null) return false;

		// Check expiry
		ACell expCell = claims.get(EXP);
		if (expCell != null) {
			try {
				long exp = Long.parseLong(expCell.toString());
				long nowSecs = System.currentTimeMillis() / 1000;
				if (nowSecs > exp) return false;
			} catch (NumberFormatException e) {
				return false;
			}
		}

		// Check issuer
		if (expectedIssuer != null) {
			AString iss = RT.ensureString(claims.get(ISS));
			if (iss == null || !expectedIssuer.equals(iss.toString())) return false;
		}

		// Check audience
		if (expectedAudience != null) {
			ACell audCell = claims.get(AUD);
			if (audCell == null) return false;
			AString aud = RT.ensureString(audCell);
			if (aud == null || !expectedAudience.equals(aud.toString())) return false;
		}

		return true;
	}

	// ========== Static signing methods (unchanged) ==========

	/**
	 * Get the claims string for a JWT before encoding
	 * @param claimData Structured claim data
	 * @return Claims String in UTF-8 JSON
	 */
	public static AString claims(AMap<AString,ACell> claimData) {
		return JSON.toAString(claimData);
	}

	public static AString build(AString header, AString claims, ABlob sig) {
		BlobBuilder bb=new BlobBuilder();
		bb.append(encoder.encode(header.getBytes()));
		bb.append('.');
		bb.append(encoder.encode(claims.getBytes()));
		bb.append('.');
		bb.append(encoder.encode(sig.getBytes()));
		return bb.toAString();
	}

	public static AString encode(ABlobLike<?> data) {
		return encode(data.getBytes());
	}

	public static AString encode(byte[] data) {
		return Strings.wrap(encoder.encode(data));
	}

	public static byte[] decodeRaw(AString encodedBase64) {
		return decoder.decode(encodedBase64.getBytes());
	}

	public static AString signHS256(ABlobLike<?> message, ABlobLike<?> secret) {
		return signHS256(message,secret.getBytes());
	}

	public static AString signHS256(ABlobLike<?> message, byte[] secret) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			SecretKeySpec keySpec = new SecretKeySpec(secret, "HmacSHA256");
			mac.init(keySpec);
			byte[] rawSignature = mac.doFinal(message.getBytes());
			return encode(rawSignature);
		} catch (NoSuchAlgorithmException  e) {
			throw new Error("HMAC algorithm failure", e);
		} catch (InvalidKeyException e) {
			throw new IllegalArgumentException("Invalid key",e);
		}
	}

	/**
	 * Build and sign a symmetric (HS256) JSON Web Token from claim data.
	 *
	 * @param claimData Claims to embed in the JWT payload as an {@link AMap} of
	 *                  {@link AString} keys to {@link ACell} values.
	 * @param secret    Shared secret key material used for HMAC signing.
	 * @return The encoded JWT string containing header, payload, and signature.
	 */
	public static AString signSymmetric(AMap<AString,ACell> claimData, ABlobLike<?> secret) {
		AString claimString = claims(claimData);
		AString base64Claims = encode(claimString);
		AString toSign = HEADER_HS256.append(".").append(base64Claims);
		AString signature = signHS256(toSign, secret);
		return toSign.append(".").append(signature);
	}

	/**
	 * Build and sign a public-key (Ed25519 / EdDSA) JSON Web Token from claim data.
	 *
	 * @param claimData Claims to embed in the JWT payload as an {@link AMap} of
	 *                  {@link AString} keys to {@link ACell} values.
	 * @param keyPair   Key pair used to produce the Ed25519 signature. The
	 *                  multikey-encoded public key is placed in the {@code kid}
	 *                  header parameter.
	 * @return The encoded JWT string containing header, payload, and signature.
	 */
	public static AString signPublic(AMap<AString,ACell> claimData, AKeyPair keyPair) {
		AString kid = Multikey.encodePublicKey(keyPair.getAccountKey());
		AMap<AString,ACell> headerMap = HEADER_EDDSA_BASE.assoc(Strings.create("kid"), kid);
		AString headerString = claims(headerMap);
		AString base64Header = encode(headerString);

		AString claimString = claims(claimData);
		AString base64Claims = encode(claimString);
		AString toSign = base64Header.append(".").append(base64Claims);
		ASignature signature = keyPair.sign(Blob.wrap(toSign.getBytes()));
		AString encodedSig = encode(signature.getBytes());
		return toSign.append(".").append(encodedSig);
	}

	// ========== Static verification methods (backward compat, delegate to instance) ==========

	/**
	 * Verify an HS256 JWT against a shared secret.
	 *
	 * @param jwt The encoded JWT string
	 * @param secret Shared secret key bytes
	 * @return true if signature is valid
	 */
	public static boolean verifyHS256(AString jwt, byte[] secret) {
		int lastdot=jwt.toString().lastIndexOf('.');
		if (lastdot<0) throw new IllegalArgumentException("Invalid JWT format, missing last dot");
		AString msg=jwt.slice(0,lastdot);
		AString sig=signHS256(msg,secret);
		return sig.equals(jwt.slice(lastdot+1));
	}

	/**
	 * Verify an EdDSA (Ed25519) JWT and return the claims if valid.
	 *
	 * Extracts the public key from the {@code kid} header parameter (multikey format),
	 * verifies the Ed25519 signature, and returns the parsed claims map.
	 *
	 * @param jwt The encoded JWT string
	 * @return Claims map if signature is valid, or null if verification fails
	 */
	public static AMap<AString,ACell> verifyPublic(AString jwt) {
		JWT parsed = parse(jwt);
		if (parsed == null) return null;
		if (!parsed.verifyEdDSA()) return null;
		return parsed.getClaims();
	}

	/**
	 * Verify an EdDSA (Ed25519) JWT against a specific trusted public key.
	 *
	 * Unlike {@link #verifyPublic(AString)}, this method does not use the {@code kid}
	 * header to determine the verification key. Instead, it verifies against the
	 * provided trusted key. This is used for venue-signed JWTs where the issuer's
	 * key is known out-of-band.
	 *
	 * @param jwt The encoded JWT string
	 * @param trustedKey The public key to verify against
	 * @return Claims map if signature is valid, or null if verification fails
	 */
	public static AMap<AString,ACell> verifyPublic(AString jwt, AccountKey trustedKey) {
		JWT parsed = parse(jwt);
		if (parsed == null) return null;
		if (!parsed.verifyEdDSA(trustedKey)) return null;
		return parsed.getClaims();
	}

	/**
	 * Verify an RS256 (RSA-SHA256) JWT and return the claims if valid.
	 *
	 * Used for validating external OAuth provider tokens where the provider's
	 * RSA public key has been resolved (e.g. from a JWKS endpoint).
	 *
	 * @param jwt The encoded JWT string
	 * @param publicKey RSA public key to verify against
	 * @return Claims map if signature is valid, or null if verification fails
	 */
	public static AMap<AString,ACell> verifyRS256(AString jwt, RSAPublicKey publicKey) {
		JWT parsed = parse(jwt);
		if (parsed == null) return null;
		if (!parsed.verifyRS256(publicKey)) return null;
		return parsed.getClaims();
	}
}
