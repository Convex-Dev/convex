package convex.auth.ucan;

import java.nio.charset.StandardCharsets;

import convex.auth.did.DIDVerifier;
import convex.auth.jwt.JWT;
import convex.core.crypto.ASignature;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Vectors;
import convex.core.lang.RT;

/**
 * Validates UCAN tokens in two separated layers:
 *
 * <p><b>Integrity</b> ({@link #validate(UCAN, long, DIDVerifier)},
 * {@link #validateJWT(AString, long, DIDVerifier)}, threaded through
 * {@link #parseTransportUCANs(AVector, DIDVerifier)}) — signature at every hop via an
 * injected {@link DIDVerifier}, temporal bounds, and chain linkage
 * (proof.aud == token.iss) with temporal narrowing (token.exp ≤ proof.exp). Passing
 * integrity means "genuine and chain-consistent" — it does NOT mean the token grants
 * anything. Enforcement must go through the authority layer.</p>
 *
 * <p><b>Authority</b> ({@link #capabilitiesFor(AVector, AString, AString, AString,
 * RootAuthorityPolicy, long)}, {@link #isAuthorised}) — per requested resource + ability:
 * capability coverage first (cheap), then per-hop delegation attenuation, then root
 * authority via an injected {@link RootAuthorityPolicy}. Fail-closed throughout.</p>
 *
 * <p><b>Not checked here — by design.</b> convex-core provides primitives that every
 * UCAN consumer needs identically; stateful or application-specific policy is left to
 * callers. The following are deliberately out of scope:</p>
 * <ul>
 *   <li><b>Replay protection ({@code nnc}).</b> Requires a nonce store;
 *       the correct scope (per-user, per-session, per-resource) and
 *       retention window depend on the application.</li>
 *   <li><b>Revocation.</b> Requires a revocation list; transport and
 *       durability are application concerns.</li>
 *   <li><b>Audience policy.</b> Whether {@code aud} must equal the
 *       receiving party's DID, or some other rule, is a caller decision.</li>
 *   <li><b>Custodial / non-self-sovereign trust.</b> Injected via a caller-supplied
 *       {@link RootAuthorityPolicy} (compose with
 *       {@link RootAuthorityPolicy#or(RootAuthorityPolicy)}).</li>
 *   <li><b>{@code ucv} version negotiation</b> and {@code fct} (facts)
 *       handling — not required for transport validity.</li>
 * </ul>
 */
public class UCANValidator {

	/**
	 * Validate a UCAN token's integrity: signature (via the injected verifier, at every
	 * hop of the proof chain), expiry, not-before, and chain linkage.
	 *
	 * <p><b>Integrity only.</b> A non-null result means the token is genuine and
	 * chain-consistent — NOT that it grants anything. Enforcement must go through
	 * {@link #capabilitiesFor(AVector, AString, AString, AString, RootAuthorityPolicy,
	 * long)} / {@link #isAuthorised}, which check capability coverage, delegation
	 * attenuation, and root authority.</p>
	 *
	 * <p>The verifier receives the CAD3 signing encoding of each payload and the raw
	 * signature bytes, and picks the signature scheme from the {@code iss} DID — the
	 * token's {@code alg} header is ignored (closing algorithm-confusion attacks).</p>
	 *
	 * @param token UCAN to validate
	 * @param nowSeconds Current time in unix seconds
	 * @param verifier DID signature verifier applied at every hop
	 * @return The validated UCAN on success, null on failure
	 */
	public static UCAN validate(UCAN token, long nowSeconds, DIDVerifier verifier) {
		if (token == null || verifier == null) return null;

		// 1. Verify signature via the injected verifier (fail-closed on any throw)
		if (!verifiesSafely(verifier, token.getIssuer(), token.getSigningMessage(),
				signatureBlob(token))) return null;

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
				AMap<AString, ACell> proofMap = RT.castMap(proofCell);
				if (proofMap == null) return null;

				UCAN proof = UCAN.parse(proofMap);
				if (proof == null) return null;

				// Recursively validate the proof with the same verifier
				if (validate(proof, nowSeconds, verifier) == null) return null;

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
	 * Validate a UCAN token with the default {@code did:key}-only verifier.
	 *
	 * @param token UCAN to validate
	 * @param nowSeconds Current time in unix seconds
	 * @return The validated UCAN on success, null on failure
	 * @deprecated hardcodes {@code did:key} verification so any chain containing another
	 *             DID method fails; use {@link #validate(UCAN, long, DIDVerifier)} with
	 *             an explicit verifier (e.g. {@link DIDVerifier#CONVEX} or
	 *             {@link DIDVerifier#forState}).
	 */
	@Deprecated
	public static UCAN validate(UCAN token, long nowSeconds) {
		return validate(token, nowSeconds, DIDVerifier.CONVEX);
	}

	/**
	 * Validate a JWT-encoded UCAN token's integrity: signature (via the injected
	 * verifier, at every hop), expiry, not-before, and chain integrity.
	 *
	 * <p>Proof tokens in the {@code prf} claim are expected to be JWT strings (not CVM
	 * maps), and are recursively validated. The verifier receives the JWT signing input
	 * (base64url {@code header.payload} as UTF-8 bytes) — it is encoding-agnostic, always
	 * getting exactly the bytes that were signed. The verification key is bound to the
	 * {@code iss} claim; the attacker-controlled {@code kid} and {@code alg} headers are
	 * ignored.</p>
	 *
	 * @param jwtString JWT-encoded UCAN string
	 * @param nowSeconds Current time in unix seconds
	 * @param verifier DID signature verifier applied at every hop
	 * @return The validated UCAN on success, null on failure
	 */
	public static UCAN validateJWT(AString jwtString, long nowSeconds, DIDVerifier verifier) {
		if (jwtString == null || verifier == null) return null;

		JWT parsed = JWT.parse(jwtString);
		if (parsed == null) return null;
		AMap<AString, ACell> claims = parsed.getClaims();
		if (claims == null) return null;

		// Verify signature against the iss DID via the injected verifier
		AString iss = RT.ensureString(claims.get(UCAN.ISS));
		Blob message = Blob.wrap(parsed.getSigningInput().getBytes(StandardCharsets.UTF_8));
		Blob sigBlob = Blob.wrap(parsed.getSignatureBytes());
		if (!verifiesSafely(verifier, iss, message, sigBlob)) return null;

		UCAN token;
		try {
			token = UCAN.fromPayload(claims, ASignature.fromBlob(sigBlob));
		} catch (Exception e) {
			return null;
		}

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

				// Recursively validate proof JWT with the same verifier
				UCAN proof = validateJWT(proofJwt, nowSeconds, verifier);
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
	 * Validate a JWT-encoded UCAN token with the default {@code did:key}-only verifier.
	 *
	 * @param jwtString JWT-encoded UCAN string
	 * @param nowSeconds Current time in unix seconds
	 * @return The validated UCAN on success, null on failure
	 */
	public static UCAN validateJWT(AString jwtString, long nowSeconds) {
		return validateJWT(jwtString, nowSeconds, DIDVerifier.CONVEX);
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
	 * <p>Each element must be a JWT string. Every returned token has had its signature
	 * verified via the given verifier against its {@code iss} DID (not the JWT
	 * {@code kid} header), its temporal bounds checked, and its proof chain recursively
	 * validated. Tokens that fail any check are silently dropped — invalid tokens never
	 * appear in the returned vector.</p>
	 *
	 * <p><b>Trust boundary:</b> this is the single point at which UCAN
	 * signatures are verified for inbound requests. Downstream code that
	 * consumes the returned vector (typically via
	 * {@code RequestContext.withProofs()}) may rely on signature and chain
	 * integrity without re-checking. Only temporal bounds and policy
	 * (audience, attenuation, root authority) need to be re-evaluated at use time —
	 * verify once at the boundary, filter many per request.</p>
	 *
	 * @param ucans Vector of JWT strings from the transport layer, or null
	 * @param verifier DID signature verifier applied at every hop
	 * @return Vector of cryptographically-verified UCAN payload maps, or null
	 *         if the input was null or no tokens verified
	 */
	public static AVector<ACell> parseTransportUCANs(AVector<ACell> ucans, DIDVerifier verifier) {
		if (ucans == null || ucans.isEmpty() || verifier == null) return null;
		long now = System.currentTimeMillis() / 1000;
		AVector<ACell> result = Vectors.empty();
		for (long i = 0; i < ucans.count(); i++) {
			AString jwt = RT.ensureString(ucans.get(i));
			if (jwt == null) continue;
			UCAN validated = validateJWT(jwt, now, verifier);
			if (validated == null) continue;
			result = result.conj(validated.toMap());
		}
		return result.isEmpty() ? null : result;
	}

	/**
	 * Parse a transport-level {@code ucans} vector with the default
	 * {@code did:key}-only verifier. See {@link #parseTransportUCANs(AVector, DIDVerifier)}.
	 *
	 * @param ucans Vector of JWT strings from the transport layer, or null
	 * @return Vector of cryptographically-verified UCAN payload maps, or null
	 */
	public static AVector<ACell> parseTransportUCANs(AVector<ACell> ucans) {
		return parseTransportUCANs(ucans, DIDVerifier.CONVEX);
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
	 * @param verifier DID signature verifier applied at every hop
	 * @return Vector of cryptographically-verified UCAN payload maps, or null
	 *         if no tokens verified
	 */
	public static AVector<ACell> parseTransportUCANsWithBearer(AString bearer,
			AVector<ACell> bodyUcans, DIDVerifier verifier) {
		if (bearer == null) return parseTransportUCANs(bodyUcans, verifier);
		AVector<ACell> combined = Vectors.of(bearer);
		if (bodyUcans != null && !bodyUcans.isEmpty()) combined = combined.concat(bodyUcans);
		return parseTransportUCANs(combined, verifier);
	}

	/**
	 * Parse transport UCANs from bearer + body with the default {@code did:key}-only
	 * verifier. See {@link #parseTransportUCANsWithBearer(AString, AVector, DIDVerifier)}.
	 *
	 * @param bearer Bearer JWT from the Authorization header, or null
	 * @param bodyUcans Vector of JWT strings from the request body, or null
	 * @return Vector of cryptographically-verified UCAN payload maps, or null
	 */
	public static AVector<ACell> parseTransportUCANsWithBearer(AString bearer, AVector<ACell> bodyUcans) {
		return parseTransportUCANsWithBearer(bearer, bodyUcans, DIDVerifier.CONVEX);
	}

	/**
	 * Collect the capabilities that authorise a specific request, from a set of
	 * already-verified proof tokens.
	 *
	 * <p>For each token presented to {@code audience}, a capability is included iff:</p>
	 * <ol>
	 *   <li><b>Coverage (cheap, first):</b> the capability {@link Capability#covers}
	 *       the requested resource + ability, and the token's temporal bounds still hold
	 *       (chain temporal narrowing was already enforced at ingress);</li>
	 *   <li><b>Delegation attenuation, per hop:</b> walking the token's {@code prf} chain
	 *       to its root, every proof's {@code att} must contain a capability covering the
	 *       request — a delegate can never hold more than it was granted;</li>
	 *   <li><b>Root authority:</b> the chain's root issuer is accepted by the given
	 *       {@link RootAuthorityPolicy} for the granted resource. A resource with no
	 *       derivable owner grants nothing (fail-closed at the capability, not the
	 *       token).</li>
	 * </ol>
	 *
	 * <p>Returned capability maps retain their caveats ({@code nb}) so callers can
	 * enforce them. <b>Assumes signatures and chains are already verified</b> at the
	 * transport boundary ({@link #parseTransportUCANs}). <b>Fail-closed:</b> null
	 * arguments, malformed tokens, and throwing policies all yield no capabilities —
	 * never a wildcard. Root-authority decisions are stable per (root issuer, resource)
	 * within a policy's horizon, so callers may memoise the policy.</p>
	 *
	 * @param proofs verified proof payload maps (as produced by
	 *        {@link #parseTransportUCANs}), or null
	 * @param audience the audience DID a token must name (e.g. the caller)
	 * @param requestWith the requested resource
	 * @param requestCan the requested ability
	 * @param policy root-authority policy (e.g. {@link RootAuthorityPolicy#SELF_SOVEREIGN})
	 * @param nowSeconds current time in unix seconds
	 * @return the authorising capabilities, or null if none
	 */
	public static AVector<ACell> capabilitiesFor(AVector<ACell> proofs, AString audience,
			AString requestWith, AString requestCan, RootAuthorityPolicy policy, long nowSeconds) {
		return collectAuthorised(proofs, audience, requestWith, requestCan, policy, nowSeconds, false);
	}

	/**
	 * Check whether the presented proofs authorise a specific request. Same checks as
	 * {@link #capabilitiesFor(AVector, AString, AString, AString, RootAuthorityPolicy,
	 * long)} but short-circuits on the first covering, chain-attenuated,
	 * root-authorised capability.
	 *
	 * @param proofs verified proof payload maps, or null
	 * @param audience the audience DID a token must name
	 * @param requestWith the requested resource
	 * @param requestCan the requested ability
	 * @param policy root-authority policy
	 * @param nowSeconds current time in unix seconds
	 * @return true iff some capability authorises the request
	 */
	public static boolean isAuthorised(AVector<ACell> proofs, AString audience,
			AString requestWith, AString requestCan, RootAuthorityPolicy policy, long nowSeconds) {
		return collectAuthorised(proofs, audience, requestWith, requestCan, policy, nowSeconds, true) != null;
	}

	/**
	 * Collect the capabilities a set of already-verified proof tokens grants to
	 * a given audience under a given issuer.
	 *
	 * <p>Returns the union of the capabilities ({@code att}) of every proof whose
	 * {@code aud} equals {@code audience}, whose {@code iss} equals {@code issuer},
	 * and whose temporal bounds still hold at {@code nowSeconds}.</p>
	 *
	 * @param proofs verified proof payload maps, or null
	 * @param audience the audience DID a token must name (e.g. the caller)
	 * @param issuer the issuer DID a token must carry (e.g. the trusted authority)
	 * @param nowSeconds current time in unix seconds
	 * @return the union of matching capabilities, or null if none match
	 * @deprecated the {@code issuer} parameter was an interim "root must be the trusted
	 *             issuer" stopgap that only matches single-hop tokens; use
	 *             {@link #capabilitiesFor(AVector, AString, AString, AString,
	 *             RootAuthorityPolicy, long)}, which checks delegation attenuation and
	 *             root authority across full chains.
	 */
	@Deprecated
	public static AVector<ACell> capabilitiesFor(AVector<ACell> proofs,
			AString audience, AString issuer, long nowSeconds) {
		if (proofs == null || audience == null || issuer == null) return null;
		AVector<ACell> result = Vectors.empty();
		for (long i = 0; i < proofs.count(); i++) {
			AMap<AString, ACell> tokenMap = RT.castMap(proofs.get(i));
			if (tokenMap == null) continue;
			UCAN token = UCAN.parse(tokenMap);
			if (token == null) continue;
			if (!checkTemporalBounds(token, nowSeconds)) continue;
			if (!audience.equals(token.getAudience())) continue;
			if (!issuer.equals(token.getIssuer())) continue;
			result = result.concat(token.getCapabilities());
		}
		return result.isEmpty() ? null : result;
	}

	// ==================== Internal helpers ====================

	private static AVector<ACell> collectAuthorised(AVector<ACell> proofs, AString audience,
			AString requestWith, AString requestCan, RootAuthorityPolicy policy, long nowSeconds,
			boolean shortCircuit) {
		if (proofs == null || audience == null || requestWith == null || requestCan == null
				|| policy == null) return null;
		AVector<ACell> result = Vectors.empty();
		long np = proofs.count();
		for (long i = 0; i < np; i++) {
			AMap<AString, ACell> tokenMap = RT.castMap(proofs.get(i));
			if (tokenMap == null) continue;
			UCAN token = UCAN.parse(tokenMap);
			if (token == null) continue;
			if (!checkTemporalBounds(token, nowSeconds)) continue;
			if (!audience.equals(token.getAudience())) continue;

			AVector<ACell> att = token.getCapabilities();
			long nc = (att == null) ? 0 : att.count();
			for (long j = 0; j < nc; j++) {
				AMap<AString, ACell> cap = RT.castMap(att.get(j));
				if (cap == null) continue;
				// Cheap filter first: no authority work for capabilities that don't cover
				if (!coversSafely(cap, requestWith, requestCan)) continue;
				AString grantWith = RT.ensureString(cap.get(Capability.WITH));
				if (!chainAuthorised(token, requestWith, requestCan, grantWith, policy)) continue;
				result = result.conj(cap);
				if (shortCircuit) return result;
			}
		}
		return result.isEmpty() ? null : result;
	}

	/**
	 * Walk a token's proof chain to its root, enforcing per-hop delegation attenuation,
	 * and check root authority at the root. A token with no proofs is itself the root.
	 * Multi-proof tokens are authorised if ANY attenuation-respecting path to an accepted
	 * root exists.
	 */
	private static boolean chainAuthorised(UCAN token, AString requestWith, AString requestCan,
			AString grantWith, RootAuthorityPolicy policy) {
		AVector<ACell> prfs = token.getProofs();
		if (prfs == null || prfs.isEmpty()) {
			// This token is the root of the chain
			try {
				return policy.acceptsRoot(token.getIssuer(), grantWith);
			} catch (Throwable t) {
				return false; // defective policy fails closed
			}
		}
		long n = prfs.count();
		for (long i = 0; i < n; i++) {
			UCAN proof = parseProof(prfs.get(i));
			if (proof == null) continue;
			// Per-hop attenuation: the proof must itself grant coverage of the request
			if (!attCovers(proof, requestWith, requestCan)) continue;
			if (chainAuthorised(proof, requestWith, requestCan, grantWith, policy)) return true;
		}
		return false;
	}

	/**
	 * Parse a proof entry in either encoding: a CVM token map, or a JWT string (as found
	 * in {@code prf} of JWT-origin tokens). Integrity was already verified at ingress, so
	 * no signature re-check is needed here.
	 */
	private static UCAN parseProof(ACell proofCell) {
		if (proofCell == null) return null;
		AString jwt = RT.ensureString(proofCell);
		if (jwt != null) return UCAN.parseJWT(jwt);
		AMap<AString, ACell> map = RT.castMap(proofCell);
		if (map == null) return null;
		return UCAN.parse(map);
	}

	/** Whether any capability in the token's {@code att} covers the request. */
	private static boolean attCovers(UCAN token, AString requestWith, AString requestCan) {
		AVector<ACell> att = token.getCapabilities();
		if (att == null) return false;
		long n = att.count();
		for (long i = 0; i < n; i++) {
			AMap<AString, ACell> cap = RT.castMap(att.get(i));
			if (cap == null) continue;
			if (coversSafely(cap, requestWith, requestCan)) return true;
		}
		return false;
	}

	private static boolean coversSafely(AMap<AString, ACell> cap, AString requestWith, AString requestCan) {
		try {
			return Capability.covers(cap, requestWith, requestCan);
		} catch (Throwable t) {
			return false; // malformed capability grants nothing
		}
	}

	private static boolean verifiesSafely(DIDVerifier verifier, AString did, Blob message, Blob signature) {
		if (did == null || message == null || signature == null) return false;
		try {
			return verifier.verifies(did, message, signature);
		} catch (Throwable t) {
			return false; // defective verifier fails closed
		}
	}

	private static Blob signatureBlob(UCAN token) {
		ASignature sig = token.getSignature();
		if (sig == null) return null;
		return Blob.wrap(sig.getBytes());
	}
}
