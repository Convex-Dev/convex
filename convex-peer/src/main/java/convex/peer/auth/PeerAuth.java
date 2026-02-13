package convex.peer.auth;

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

	/**
	 * Creates a PeerAuth instance for the given peer.
	 *
	 * @param peerKeyPair The peer's Ed25519 key pair (for signing peer tokens)
	 */
	public PeerAuth(AKeyPair peerKeyPair) {
		if (peerKeyPair == null) throw new IllegalArgumentException("Peer key pair required");
		this.peerKeyPair = peerKeyPair;
		this.peerKey = peerKeyPair.getAccountKey();
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
		AString peerMultikey = Multikey.encodePublicKey(peerKey);

		AMap<AString, ACell> claims = Maps.of(
			Strings.create("sub"), identity,
			Strings.create("iss"), Strings.create("did:key:").append(peerMultikey),
			Strings.create("iat"), CVMLong.create(now),
			Strings.create("exp"), CVMLong.create(now + lifetimeSeconds)
		);

		return JWT.signPublic(claims, peerKeyPair);
	}

	/**
	 * Gets the peer's public key.
	 */
	public AccountKey getPeerKey() {
		return peerKey;
	}

	// ==================== Internal ====================

	private AString verifySelfIssued(JWT parsed) {
		try {
			String kid = parsed.getKeyID();
			if (kid == null) return null;

			AccountKey signerKey = Multikey.decodePublicKey(kid);
			if (signerKey == null) return null;

			if (!parsed.verifyEdDSA(signerKey)) return null;
			if (!parsed.validateClaims(null, null)) return null;

			// Identity is the sub claim
			AString sub = RT.ensureString(parsed.getClaims().get(Strings.create("sub")));
			if (sub == null) return null;

			// Self-issued sub must match did:key for the signing key
			AString expectedDID = Strings.create("did:key:").append(Multikey.encodePublicKey(signerKey));
			if (!expectedDID.equals(sub)) return null;

			return sub;
		} catch (Exception e) {
			return null;
		}
	}

	private AString verifyPeerSigned(JWT parsed) {
		try {
			if (!parsed.verifyEdDSA(peerKey)) return null;
			if (!parsed.validateClaims(null, null)) return null;

			// Identity is the sub claim
			AString sub = RT.ensureString(parsed.getClaims().get(Strings.create("sub")));
			return sub;
		} catch (Exception e) {
			return null;
		}
	}
}
