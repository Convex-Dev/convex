package convex.auth.ucan;

import convex.auth.jwt.JWT;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.core.lang.RT;

/**
 * Validates UCAN tokens: signature, temporal bounds, and chain integrity.
 *
 * <p>Validation is recursive: each proof in the chain is validated, and
 * chain linkage (proof.aud == token.iss) and temporal narrowing
 * (token.exp ≤ proof.exp) are enforced at every link.</p>
 *
 * <p><b>Not checked here — by design.</b> convex-core provides primitives
 * that every UCAN consumer needs identically; stateful or application-
 * specific policy is left to callers. The following are deliberately out
 * of scope:</p>
 * <ul>
 *   <li><b>Replay protection ({@code nnc}).</b> Requires a nonce store;
 *       the correct scope (per-user, per-session, per-resource) and
 *       retention window depend on the application.</li>
 *   <li><b>Revocation.</b> Requires a revocation list; transport and
 *       durability are application concerns.</li>
 *   <li><b>Audience policy.</b> Whether {@code aud} must equal the
 *       receiving party's DID, or some other rule, is a caller decision.</li>
 *   <li><b>Issuer policy.</b> E.g. "only accept venue-issued top-level
 *       tokens" — application-specific trust model.</li>
 *   <li><b>Capability attenuation matching.</b> Lives in
 *       {@link Capability#covers} as a separate primitive; not invoked here.</li>
 *   <li><b>{@code ucv} version negotiation</b> and {@code fct} (facts)
 *       handling — not required for transport validity.</li>
 * </ul>
 */
public class UCANValidator {

	/**
	 * Validate a UCAN token: signature, expiry, not-before, and chain integrity.
	 *
	 * @param token UCAN to validate
	 * @param nowSeconds Current time in unix seconds
	 * @return The validated UCAN on success, null on failure
	 */
	public static UCAN validate(UCAN token, long nowSeconds) {
		if (token == null) return null;

		// 1. Verify signature
		if (!token.verifySignature()) return null;

		// 2. Check expiry
		if (token.getExpiry() <= nowSeconds) return null;

		// 3. Check not-before
		Long nbf = token.getNotBefore();
		if (nbf != null && nbf > nowSeconds) return null;

		// 4. Validate proof chain
		AVector<ACell> proofs = token.getProofs();
		if (proofs != null && proofs.count() > 0) {
			AString tokenIss = token.getIssuer();
			long tokenExp = token.getExpiry();

			for (long i = 0; i < proofs.count(); i++) {
				ACell proofCell = proofs.get(i);

				// Parse proof as UCAN
				AMap<AString, ACell> proofMap = RT.ensureMap(proofCell);
				if (proofMap == null) return null;

				UCAN proof = UCAN.parse(proofMap);
				if (proof == null) return null;

				// Recursively validate the proof
				if (validate(proof, nowSeconds) == null) return null;

				// Chain link: proof.aud must equal token.iss
				AString proofAud = proof.getAudience();
				if (proofAud == null || !proofAud.equals(tokenIss)) return null;

				// Temporal narrowing: token.exp must be ≤ proof.exp
				if (tokenExp > proof.getExpiry()) return null;
			}
		}

		return token;
	}

	/**
	 * Validate a JWT-encoded UCAN token: EdDSA signature, expiry, not-before,
	 * and chain integrity.
	 *
	 * <p>Proof tokens in the {@code prf} claim are expected to be JWT strings
	 * (not CVM maps), and are recursively validated.</p>
	 *
	 * @param jwtString JWT-encoded UCAN string
	 * @param nowSeconds Current time in unix seconds
	 * @return The validated UCAN on success, null on failure
	 */
	public static UCAN validateJWT(AString jwtString, long nowSeconds) {
		// Parse and verify JWT signature
		UCAN token = UCAN.fromJWT(jwtString);
		if (token == null) return null;

		// Check expiry
		if (token.getExpiry() <= nowSeconds) return null;

		// Check not-before
		Long nbf = token.getNotBefore();
		if (nbf != null && nbf > nowSeconds) return null;

		// Validate proof chain (proofs are JWT strings)
		AVector<ACell> proofs = token.getProofs();
		if (proofs != null && proofs.count() > 0) {
			AString tokenIss = token.getIssuer();
			long tokenExp = token.getExpiry();

			for (long i = 0; i < proofs.count(); i++) {
				AString proofJwt = RT.ensureString(proofs.get(i));
				if (proofJwt == null) return null;

				// Recursively validate proof JWT
				UCAN proof = validateJWT(proofJwt, nowSeconds);
				if (proof == null) return null;

				// Chain link: proof.aud must equal token.iss
				AString proofAud = proof.getAudience();
				if (proofAud == null || !proofAud.equals(tokenIss)) return null;

				// Temporal narrowing: token.exp must be ≤ proof.exp
				if (tokenExp > proof.getExpiry()) return null;
			}
		}

		return token;
	}

	/**
	 * Re-check only the temporal bounds ({@code exp}, {@code nbf}) of a UCAN
	 * whose signature and chain have already been verified.
	 *
	 * <p>This is intended for use at dispatch time, after the token has been
	 * cryptographically verified at transport ingress (see
	 * {@link #parseTransportUCANs}). It guards against the narrow window in
	 * which a token may have expired between ingress and use, without
	 * redoing the (expensive) signature check — which in any case cannot be
	 * repeated for JWT-origin tokens because their stored signature covers
	 * JWT-encoded bytes, not CVM-encoded bytes.</p>
	 *
	 * @param token Already-verified UCAN
	 * @param nowSeconds Current time in unix seconds
	 * @return true if the token is still within its temporal bounds
	 */
	public static boolean checkTemporalBounds(UCAN token, long nowSeconds) {
		if (token == null) return false;
		if (token.getExpiry() <= nowSeconds) return false;
		Long nbf = token.getNotBefore();
		if (nbf != null && nbf > nowSeconds) return false;
		return true;
	}

	/**
	 * Parse a transport-level {@code ucans} vector into validated UCAN maps.
	 *
	 * <p>Each element must be a JWT string. Every returned token has had its
	 * EdDSA signature verified (via the JWT {@code kid} header), its temporal
	 * bounds checked, and its proof chain recursively validated. Tokens that
	 * fail any check are silently dropped — invalid tokens never appear in
	 * the returned vector.</p>
	 *
	 * <p><b>Trust boundary:</b> this is the single point at which UCAN
	 * signatures are verified for inbound requests. Downstream code that
	 * consumes the returned vector (typically via
	 * {@code RequestContext.withProofs()}) may rely on signature and chain
	 * integrity without re-checking. Only temporal bounds and policy
	 * (audience, issuer, attenuation) need to be re-evaluated at use time.</p>
	 *
	 * @param ucans Vector of JWT strings from the transport layer, or null
	 * @return Vector of cryptographically-verified UCAN payload maps, or null
	 *         if the input was null or no tokens verified
	 */
	public static AVector<ACell> parseTransportUCANs(AVector<ACell> ucans) {
		if (ucans == null || ucans.isEmpty()) return null;
		long now = System.currentTimeMillis() / 1000;
		AVector<ACell> result = Vectors.empty();
		for (long i = 0; i < ucans.count(); i++) {
			AString jwt = RT.ensureString(ucans.get(i));
			if (jwt == null) continue;
			UCAN validated = validateJWT(jwt, now);
			if (validated == null) continue;
			result = result.conj(validated.toMap());
		}
		return result.isEmpty() ? null : result;
	}

	/**
	 * Parse transport UCANs from two sources — an HTTP {@code Authorization:
	 * Bearer <jwt>} header and the request envelope's {@code ucans} array —
	 * through the same trust boundary as {@link #parseTransportUCANs}.
	 *
	 * <p>The bearer token, if non-null, is prepended to the body vector and the
	 * combined vector is validated by a single call to
	 * {@link #parseTransportUCANs}. This preserves the invariant that UCAN
	 * signatures and chains are verified at exactly one place regardless of
	 * transport.</p>
	 *
	 * <p>Matches the IETF UCAN-HTTP bearer convention: a single invocation UCAN
	 * may be sent via the {@code Authorization} header, with additional
	 * delegation tokens accompanying it in the request body.</p>
	 *
	 * @param bearer Bearer JWT from the Authorization header, or null
	 * @param bodyUcans Vector of JWT strings from the request body, or null
	 * @return Vector of cryptographically-verified UCAN payload maps, or null
	 *         if no tokens verified
	 */
	public static AVector<ACell> parseTransportUCANsWithBearer(AString bearer, AVector<ACell> bodyUcans) {
		if (bearer == null) return parseTransportUCANs(bodyUcans);
		AVector<ACell> combined = Vectors.of(bearer);
		if (bodyUcans != null && !bodyUcans.isEmpty()) combined = combined.concat(bodyUcans);
		return parseTransportUCANs(combined);
	}
}
