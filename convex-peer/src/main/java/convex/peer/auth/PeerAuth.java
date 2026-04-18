package convex.peer.auth;

import convex.auth.did.DID;
import convex.auth.jwt.JWT;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.util.Multikey;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Maps;
import convex.core.lang.RT;

/**
 * Peer authentication service — verifies bearer tokens and issues peer-signed JWTs.
 *
 * Supports two authentication paths:
 * <ol>
 *   <li><b>Self-issued EdDSA JWT</b> — client presents a JWT signed with its own
 *       Ed25519 key. The {@code kid} header contains the multikey-encoded public key.
 *       Identity is the {@code sub} claim ({@code did:key:...}).</li>
 *   <li><b>Peer-signed EdDSA JWT</b> — peer issues a JWT after social login.
 *       Verified against the peer's known public key. Identity is the {@code sub}
 *       claim ({@code did:web:...}).</li>
 * </ol>
 */
public class PeerAuth {

	private final AKeyPair peerKeyPair;
	private final AccountKey peerKey;
	private final AString expectedAudience;

	/**
	 * Creates a PeerAuth instance for the given peer.
	 *
	 * @param peerKeyPair The peer's Ed25519 key pair (for signing peer tokens)
	 */
	public PeerAuth(AKeyPair peerKeyPair) {
		this(peerKeyPair, (AString) null);
	}

	/**
	 * Creates a PeerAuth instance with audience checking.
	 *
	 * <p>When {@code expectedAudience} is non-null, all verified tokens must
	 * contain a matching {@code aud} claim. A natural audience value is the
	 * server's DID: {@code did:key:<multikey>}.
	 *
	 * @param peerKeyPair The peer's Ed25519 key pair (for signing peer tokens)
	 * @param expectedAudience Expected {@code aud} claim, or null to skip audience checking
	 */
	public PeerAuth(AKeyPair peerKeyPair, AString expectedAudience) {
		if (peerKeyPair == null) throw new IllegalArgumentException("Peer key pair required");
		this.peerKeyPair = peerKeyPair;
		this.peerKey = peerKeyPair.getAccountKey();
		this.expectedAudience = expectedAudience;
	}

	/**
	 * Verifies a bearer token (JWT) and returns the authenticated identity.
	 *
	 * Tries self-issued verification first (kid header → public key → verify).
	 * If that fails, tries peer-signed verification (verify against peer's key).
	 *
	 * @param jwt The encoded JWT string
	 * @return Authenticated identity (DID string) as AString, or null if verification fails
	 */
	public AString verifyBearerToken(AString jwt) {
		if (jwt == null) return null;

		JWT parsed = JWT.parse(jwt);
		if (parsed == null) return null;

		// Try self-issued: kid header contains multikey public key
		AString identity = verifySelfIssued(parsed);
		if (identity != null) return identity;

		// Try peer-signed: verify against peer's known key
		identity = verifyPeerSigned(parsed);
		if (identity != null) return identity;

		return null;
	}

	/**
	 * Issues a peer-signed JWT for the given identity (e.g. after social login).
	 *
	 * @param identity The authenticated identity (DID string)
	 * @param lifetimeSeconds Token lifetime in seconds
	 * @return Encoded JWT string
	 */
	public AString issuePeerToken(AString identity, long lifetimeSeconds) {
		long now = System.currentTimeMillis() / 1000;
		AMap<AString, ACell> claims = Maps.of(
			JWT.SUB, identity,
			JWT.ISS, DID.forKey(peerKey),
			JWT.IAT, now,
			JWT.EXP, now + lifetimeSeconds
		);

		if (expectedAudience != null) {
			claims = claims.assoc(JWT.AUD, expectedAudience);
		}

		return JWT.signPublic(claims, peerKeyPair);
	}

	/**
	 * Gets the peer's public key.
	 */
	public AccountKey getPeerKey() {
		return peerKey;
	}

	/**
	 * Gets the expected audience, or null if audience checking is disabled.
	 */
	public AString getExpectedAudience() {
		return expectedAudience;
	}

	/**
	 * Creates a PeerAuth with audience set to this peer's DID ({@code did:key:<multikey>}).
	 *
	 * @param peerKeyPair The peer's Ed25519 key pair
	 * @return PeerAuth instance with audience checking enabled
	 */
	public static PeerAuth createWithDIDAudience(AKeyPair peerKeyPair) {
		AString aud = DID.forKey(peerKeyPair.getAccountKey());
		return new PeerAuth(peerKeyPair, aud);
	}

	// ==================== Internal ====================

	private AString verifySelfIssued(JWT parsed) {
		try {
			String kid = parsed.getKeyID();
			if (kid == null) return null;

			AccountKey signerKey = Multikey.decodePublicKey(kid);
			if (signerKey == null) return null;

			if (!parsed.verifyEdDSA(signerKey)) return null;
			if (!parsed.validateClaims(null, expectedAudience)) return null;

			// Identity is the sub claim
			AString sub = RT.ensureString(parsed.getClaims().get(JWT.SUB));
			if (sub == null) return null;

			// Self-issued sub must match did:key for the signing key
			AString expectedDID = DID.forKey(signerKey);
			if (!expectedDID.equals(sub)) return null;

			return sub;
		} catch (Exception e) {
			return null;
		}
	}

	private AString verifyPeerSigned(JWT parsed) {
		try {
			if (!parsed.verifyEdDSA(peerKey)) return null;
			if (!parsed.validateClaims(null, expectedAudience)) return null;

			// Identity is the sub claim
			AString sub = RT.ensureString(parsed.getClaims().get(JWT.SUB));
			return sub;
		} catch (Exception e) {
			return null;
		}
	}
}
