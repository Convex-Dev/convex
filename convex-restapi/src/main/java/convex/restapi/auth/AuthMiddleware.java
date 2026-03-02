package convex.restapi.auth;

import convex.core.data.AString;
import convex.core.data.Strings;
import convex.peer.auth.PeerAuth;
import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * Javalin middleware that extracts and verifies bearer tokens from the
 * {@code Authorization} header using {@link PeerAuth}.
 *
 * On successful verification, sets the authenticated identity as a context
 * attribute ({@link #ATTR_IDENTITY}). Downstream handlers retrieve it via
 * {@link #getIdentity(Context)}.
 *
 * Two usage modes:
 * <ul>
 *   <li><b>Optional auth</b> ({@link #handler()}) — sets identity if a valid token
 *       is present, otherwise leaves it null. Suitable for routes that work both
 *       authenticated and unauthenticated.</li>
 *   <li><b>Required auth</b> ({@link #requiredHandler()}) — returns 401 if no valid
 *       token is present. Suitable for protected routes.</li>
 * </ul>
 */
public class AuthMiddleware {

	/** Context attribute key for the authenticated identity (AString DID). */
	public static final String ATTR_IDENTITY = "auth.identity";

	private final PeerAuth peerAuth;

	public AuthMiddleware(PeerAuth peerAuth) {
		if (peerAuth == null) throw new IllegalArgumentException("PeerAuth required");
		this.peerAuth = peerAuth;
	}

	/**
	 * Returns a handler that optionally extracts identity from bearer token.
	 * Does not reject unauthenticated requests.
	 */
	public Handler handler() {
		return ctx -> {
			AString identity = extractAndVerify(ctx);
			if (identity != null) {
				ctx.attribute(ATTR_IDENTITY, identity);
			}
		};
	}

	/**
	 * Returns a handler that requires a valid bearer token.
	 * Returns 401 if no valid token is present.
	 */
	public Handler requiredHandler() {
		return ctx -> {
			AString identity = extractAndVerify(ctx);
			if (identity == null) {
				ctx.status(401);
				ctx.contentType("application/json");
				ctx.result("{\"error\":\"Authentication required\"}");
				return; // Javalin skips remaining handlers for this request
			}
			ctx.attribute(ATTR_IDENTITY, identity);
		};
	}

	/**
	 * Retrieves the authenticated identity from a Javalin context.
	 *
	 * @param ctx Javalin context
	 * @return Authenticated identity (AString DID), or null if not authenticated
	 */
	public static AString getIdentity(Context ctx) {
		return ctx.attribute(ATTR_IDENTITY);
	}

	/**
	 * Gets the PeerAuth instance used by this middleware.
	 */
	public PeerAuth getPeerAuth() {
		return peerAuth;
	}

	// ==================== Internal ====================

	private AString extractAndVerify(Context ctx) {
		String authHeader = ctx.header("Authorization");
		if (authHeader == null) return null;

		// Must be "Bearer <token>"
		if (!authHeader.startsWith("Bearer ")) return null;

		String token = authHeader.substring(7).trim();
		if (token.isEmpty()) return null;

		return peerAuth.verifyBearerToken(Strings.create(token));
	}
}
