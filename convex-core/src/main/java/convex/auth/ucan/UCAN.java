package convex.auth.ucan;

import java.security.SecureRandom;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.crypto.Ed25519Signature;
import convex.core.crypto.util.Multikey;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;

/**
 * UCAN (User Controlled Authorization Networks) token for Convex.
 *
 * <p>Tokens are native CVM values encoded via CAD3. Signatures are Ed25519
 * over the Ref encoding of the payload (matching the SignedData pattern).
 * No JWT, no JSON, no base64 — pure Convex data end-to-end.</p>
 *
 * <p>Token structure:</p>
 * <pre>
 * {"header" {"alg" "EdDSA" "ucv" "0.10.0"}
 *  "payload" {"iss" "did:key:z6Mk..." "aud" "did:key:z6Mk..." "exp" 1740009600 ...}
 *  "sig" 0x...}
 * </pre>
 */
public class UCAN {

	// Token structure keys
	public static final AString HEADER = Strings.intern("header");
	public static final AString PAYLOAD = Strings.intern("payload");
	public static final AString SIG = Strings.intern("sig");

	// Payload field keys
	public static final AString ISS = Strings.intern("iss");
	public static final AString AUD = Strings.intern("aud");
	public static final AString EXP = Strings.intern("exp");
	public static final AString NBF = Strings.intern("nbf");
	public static final AString NNC = Strings.intern("nnc");
	public static final AString ATT = Strings.intern("att");
	public static final AString PRF = Strings.intern("prf");
	public static final AString FCT = Strings.intern("fct");

	// Header field keys
	public static final AString ALG = Strings.intern("alg");
	public static final AString UCV = Strings.intern("ucv");

	// Fixed header value
	public static final AMap<AString, ACell> HEADER_VALUE = Maps.of(
		ALG, Strings.intern("EdDSA"),
		UCV, Strings.create("0.10.0")
	);

	private static final String DID_KEY_PREFIX = "did:key:";
	private static final SecureRandom RANDOM = new SecureRandom();

	// Cached fields
	private final AMap<AString, ACell> payload;
	private final ASignature signature;

	private UCAN(AMap<AString, ACell> payload, ASignature signature) {
		this.payload = payload;
		this.signature = signature;
	}

	/**
	 * Create and sign a new UCAN token.
	 *
	 * @param issuerKP Issuer's key pair (signs the token)
	 * @param audience Audience's public key
	 * @param expiry   Expiry time in unix seconds
	 * @param capabilities Capabilities vector (opaque, passed through)
	 * @param proofs   Proof tokens vector (opaque, passed through)
	 * @return New signed UCAN token
	 */
	public static UCAN create(AKeyPair issuerKP, AccountKey audience, long expiry,
			AVector<ACell> capabilities, AVector<ACell> proofs) {
		AString issDID = toDIDKey(issuerKP.getAccountKey());
		AString audDID = toDIDKey(audience);
		AString nonce = generateNonce();

		AMap<AString, ACell> payload = Maps.of(
			ISS, issDID,
			AUD, audDID,
			EXP, CVMLong.create(expiry),
			NNC, nonce,
			ATT, (capabilities != null) ? capabilities : Vectors.empty(),
			PRF, (proofs != null) ? proofs : Vectors.empty()
		);

		Blob message = Ref.get(payload).getEncoding();
		ASignature sig = issuerKP.sign(message);

		return new UCAN(payload, sig);
	}

	/**
	 * Create a UCAN token with not-before time.
	 *
	 * @param issuerKP Issuer's key pair
	 * @param audience Audience's public key
	 * @param expiry   Expiry time in unix seconds
	 * @param notBefore Not-before time in unix seconds
	 * @param capabilities Capabilities vector
	 * @param proofs   Proof tokens vector
	 * @return New signed UCAN token
	 */
	public static UCAN create(AKeyPair issuerKP, AccountKey audience, long expiry,
			long notBefore, AVector<ACell> capabilities, AVector<ACell> proofs) {
		AString issDID = toDIDKey(issuerKP.getAccountKey());
		AString audDID = toDIDKey(audience);
		AString nonce = generateNonce();

		AMap<AString, ACell> payload = Maps.of(
			ISS, issDID,
			AUD, audDID,
			EXP, CVMLong.create(expiry),
			NBF, CVMLong.create(notBefore),
			NNC, nonce,
			ATT, (capabilities != null) ? capabilities : Vectors.empty(),
			PRF, (proofs != null) ? proofs : Vectors.empty()
		);

		Blob message = Ref.get(payload).getEncoding();
		ASignature sig = issuerKP.sign(message);

		return new UCAN(payload, sig);
	}

	/**
	 * Build a UCAN payload map without signing. Use with external signing
	 * services that manage private keys server-side.
	 *
	 * @param issuerKey Issuer's public key
	 * @param audience  Audience's public key
	 * @param expiry    Expiry time in unix seconds
	 * @param notBefore Not-before time in unix seconds (null to omit)
	 * @param capabilities Capabilities vector (null for empty)
	 * @param proofs    Proof tokens vector (null for empty)
	 * @param facts     Facts value (null to omit)
	 * @return Payload map ready for signing
	 */
	public static AMap<AString, ACell> buildPayload(AccountKey issuerKey, AccountKey audience,
			long expiry, Long notBefore, AVector<ACell> capabilities, AVector<ACell> proofs, ACell facts) {
		AString issDID = toDIDKey(issuerKey);
		AString audDID = toDIDKey(audience);
		AString nonce = generateNonce();

		AMap<AString, ACell> payload = Maps.of(
			ISS, issDID,
			AUD, audDID,
			EXP, CVMLong.create(expiry),
			NNC, nonce,
			ATT, (capabilities != null) ? capabilities : Vectors.empty(),
			PRF, (proofs != null) ? proofs : Vectors.empty()
		);

		if (notBefore != null) {
			payload = payload.assoc(NBF, CVMLong.create(notBefore));
		}
		if (facts != null) {
			payload = payload.assoc(FCT, facts);
		}

		return payload;
	}

	/**
	 * Create a UCAN from a pre-built payload and an externally produced signature.
	 *
	 * @param payload Payload map (as returned by {@link #buildPayload})
	 * @param signature Ed25519 signature over the Ref encoding of the payload
	 * @return New UCAN token
	 */
	public static UCAN fromPayload(AMap<AString, ACell> payload, ASignature signature) {
		return new UCAN(payload, signature);
	}

	/**
	 * Parse a UCAN from a CVM map. Returns null if the map is malformed.
	 *
	 * @param tokenMap Map with "header", "payload", "sig" keys
	 * @return Parsed UCAN, or null if malformed
	 */
	public static UCAN parse(AMap<AString, ACell> tokenMap) {
		if (tokenMap == null) return null;

		AMap<AString, ACell> payload = RT.ensureMap(tokenMap.get(PAYLOAD));
		if (payload == null) return null;

		ACell sigCell = tokenMap.get(SIG);
		ABlob sigBlob = RT.ensureBlob(sigCell);
		if (sigBlob == null && sigCell instanceof AString) {
			// Handle hex string from JSON round-trip
			sigBlob = Blob.parse(sigCell.toString());
		}
		if (sigBlob == null || sigBlob.count() != 64) return null;

		ASignature sig = Ed25519Signature.wrap(sigBlob.getBytes());
		return new UCAN(payload, sig);
	}

	/**
	 * Get the issuer DID string (did:key:z6Mk...).
	 */
	public AString getIssuer() {
		return RT.ensureString(payload.get(ISS));
	}

	/**
	 * Get the issuer's AccountKey, decoded from the did:key.
	 * Returns null if the DID is malformed.
	 */
	public AccountKey getIssuerKey() {
		return fromDIDKey(getIssuer());
	}

	/**
	 * Get the audience DID string (did:key:z6Mk...).
	 */
	public AString getAudience() {
		return RT.ensureString(payload.get(AUD));
	}

	/**
	 * Get the audience's AccountKey, decoded from the did:key.
	 * Returns null if the DID is malformed.
	 */
	public AccountKey getAudienceKey() {
		return fromDIDKey(getAudience());
	}

	/**
	 * Get the expiry time in unix seconds.
	 */
	public long getExpiry() {
		CVMLong exp = (CVMLong) payload.get(EXP);
		return (exp != null) ? exp.longValue() : 0;
	}

	/**
	 * Get the not-before time in unix seconds. Returns null if not set.
	 */
	public Long getNotBefore() {
		CVMLong nbf = RT.ensureLong(payload.get(NBF));
		return (nbf != null) ? nbf.longValue() : null;
	}

	/**
	 * Get the capabilities vector (opaque for now).
	 */
	@SuppressWarnings("unchecked")
	public AVector<ACell> getCapabilities() {
		ACell att = payload.get(ATT);
		return (att instanceof AVector) ? (AVector<ACell>) att : Vectors.empty();
	}

	/**
	 * Get the proofs vector (opaque for now).
	 */
	@SuppressWarnings("unchecked")
	public AVector<ACell> getProofs() {
		ACell prf = payload.get(PRF);
		return (prf instanceof AVector) ? (AVector<ACell>) prf : Vectors.empty();
	}

	/**
	 * Get the nonce.
	 */
	public AString getNonce() {
		return RT.ensureString(payload.get(NNC));
	}

	/**
	 * Get the payload map.
	 */
	public AMap<AString, ACell> getPayload() {
		return payload;
	}

	/**
	 * Get the signature.
	 */
	public ASignature getSignature() {
		return signature;
	}

	/**
	 * Convert to a complete CVM map representation.
	 */
	public AMap<AString, ACell> toMap() {
		return Maps.of(
			HEADER, HEADER_VALUE,
			PAYLOAD, payload,
			SIG, signature
		);
	}

	/**
	 * Verify the token's signature against the issuer's public key.
	 * @return true if signature is valid
	 */
	public boolean verifySignature() {
		AccountKey issKey = getIssuerKey();
		if (issKey == null) return false;
		Blob message = Ref.get(payload).getEncoding();
		return signature.verify(message, issKey);
	}

	/**
	 * Convert an AccountKey to a did:key string.
	 */
	public static AString toDIDKey(AccountKey key) {
		AString multikey = Multikey.encodePublicKey(key);
		return Strings.create(DID_KEY_PREFIX + multikey);
	}

	/**
	 * Extract an AccountKey from a did:key string. Returns null if malformed.
	 */
	public static AccountKey fromDIDKey(AString did) {
		if (did == null) return null;
		String s = did.toString();
		if (!s.startsWith(DID_KEY_PREFIX)) return null;
		try {
			return Multikey.decodePublicKey(s.substring(DID_KEY_PREFIX.length()));
		} catch (Exception e) {
			return null;
		}
	}

	private static AString generateNonce() {
		byte[] bytes = new byte[12];
		RANDOM.nextBytes(bytes);
		StringBuilder sb = new StringBuilder(24);
		for (byte b : bytes) {
			sb.append(String.format("%02x", b & 0xff));
		}
		return Strings.create(sb.toString());
	}
}
