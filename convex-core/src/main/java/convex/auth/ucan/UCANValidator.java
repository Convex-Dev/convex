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
 * <p>Does not yet check capability attenuation (the contents of "att").
 * That requires further design and will be added in a later phase.</p>
 *
 * <p>Validation is recursive: each proof in the chain is validated, and
 * chain linkage (proof.aud == token.iss) and temporal narrowing
 * (token.exp ≤ proof.exp) are enforced at every link.</p>
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
	 * Parse a transport-level {@code ucans} vector into validated UCAN maps.
	 *
	 * <p>Each element should be a JWT string. Invalid tokens (malformed, expired,
	 * bad signature) are silently skipped. Returns a vector of validated UCAN
	 * payload maps suitable for {@code RequestContext.withProofs()}.</p>
	 *
	 * @param ucans Vector of JWT strings from the transport layer, or null
	 * @return Vector of validated UCAN payload maps (may be empty), or null if input is null
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
}
