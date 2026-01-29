package convex.core.json;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
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

public class JWT {

	static Base64.Decoder decoder=Base64.getUrlDecoder();
	
	// JWT encoding should be Base64URL without padding
	static Base64.Encoder encoder=Base64.getUrlEncoder().withoutPadding();
	
	static AString HEADER_HS256 = encode(Strings.intern("{\"alg\":\"HS256\",\"typ\":\"JWT\"}"));
	static final AMap<AString,ACell> HEADER_EDDSA_BASE = Maps.of(
		Strings.create("alg"), Strings.create("EdDSA"),
		Strings.create("typ"), Strings.create("JWT"));
	
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
	
	public static boolean verifyHS256(AString jwt,byte[] secret) {
		int lastdot=jwt.toString().lastIndexOf('.');
		if (lastdot<0) throw new IllegalArgumentException("Invalid JWT format, missing last dot");
		AString msg=jwt.slice(0,lastdot);
		AString sig=signHS256(msg,secret);
		return sig.equals(jwt.slice(lastdot+1));
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
	/**
	 * Verify an EdDSA (Ed25519) JWT and return the claims if valid.
	 *
	 * Extracts the public key from the {@code kid} header parameter (multikey format),
	 * verifies the Ed25519 signature, and returns the parsed claims map.
	 *
	 * @param jwt The encoded JWT string
	 * @return Claims map if signature is valid, or null if verification fails
	 */
	@SuppressWarnings("unchecked")
	public static AMap<AString,ACell> verifyPublic(AString jwt) {
		try {
			String s = jwt.toString();
			int dot1 = s.indexOf('.');
			if (dot1 < 0) return null;
			int dot2 = s.indexOf('.', dot1 + 1);
			if (dot2 < 0) return null;

			// Decode header
			String headerB64 = s.substring(0, dot1);
			AMap<AString,ACell> header = RT.ensureMap(JSON.parse(Strings.wrap(decoder.decode(headerB64))));
			if (header == null) return null;

			// Check algorithm
			AString alg = RT.ensureString(header.get(Strings.create("alg")));
			if (alg == null || !"EdDSA".equals(alg.toString())) return null;

			// Extract kid and decode public key
			AString kid = RT.ensureString(header.get(Strings.create("kid")));
			if (kid == null) return null;
			AccountKey publicKey = Multikey.decodePublicKey(kid.toString());
			if (publicKey == null) return null;

			// Verify signature
			String sigB64 = s.substring(dot2 + 1);
			byte[] sigBytes = decoder.decode(sigB64);
			ASignature sig = ASignature.fromBlob(Blob.wrap(sigBytes));
			String signingInput = s.substring(0, dot2);
			if (!sig.verify(Blob.wrap(signingInput.getBytes()), publicKey)) return null;

			// Decode and return claims
			String claimsB64 = s.substring(dot1 + 1, dot2);
			return RT.ensureMap(JSON.parse(Strings.wrap(decoder.decode(claimsB64))));
		} catch (Exception e) {
			return null; // any parsing/decoding error means invalid token
		}
	}

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
}
