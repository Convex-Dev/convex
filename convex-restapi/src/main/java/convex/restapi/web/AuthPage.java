package convex.restapi.web;

import static j2html.TagCreator.a;
import static j2html.TagCreator.button;
import static j2html.TagCreator.code;
import static j2html.TagCreator.div;
import static j2html.TagCreator.h3;
import static j2html.TagCreator.p;
import static j2html.TagCreator.pre;
import static j2html.TagCreator.small;
import static j2html.TagCreator.text;

import java.util.List;

import convex.core.data.AString;
import convex.peer.auth.PeerAuth;
import convex.restapi.RESTServer;
import convex.restapi.auth.OAuthService;
import convex.restapi.auth.OAuthService.PendingAuth;
import convex.restapi.auth.OAuthService.Provider;
import io.javalin.Javalin;
import io.javalin.http.Context;
import j2html.tags.DomContent;

/**
 * Web pages for OAuth social login.
 *
 * <p>Provides a login page with provider buttons ({@code GET /auth}) and
 * an OAuth callback handler ({@code GET /auth/callback}). Uses j2html
 * with Pico CSS for consistent styling with the Explorer and WebApp.
 *
 * <p>After successful OAuth authentication, issues a peer-signed EdDSA JWT
 * that the user can use as a bearer token for MCP and REST API calls.
 */
public class AuthPage extends AWebSite {

	public AuthPage(RESTServer restServer) {
		super(restServer);
	}

	@Override
	public void addRoutes(Javalin app) {
		app.get("/auth", this::showLoginPage);
		app.get("/auth/callback", this::handleCallback);
	}

	/**
	 * GET /auth — renders login page with provider buttons.
	 */
	private void showLoginPage(Context ctx) {
		OAuthService oauth = restServer.getOAuthService();
		if (oauth == null) {
			returnPage(ctx, "Sign In",
				p("Authentication service is not available."));
			return;
		}

		List<Provider> providers = oauth.getConfiguredProviders();
		if (providers.isEmpty()) {
			returnPage(ctx, "Sign In",
				p("No OAuth providers configured. Contact the peer operator."));
			return;
		}

		DomContent[] buttons = new DomContent[providers.size()];
		for (int i = 0; i < providers.size(); i++) {
			Provider provider = providers.get(i);
			String state = OAuthService.generateState();
			String verifier = OAuthService.generateCodeVerifier();
			String challenge = OAuthService.computeCodeChallenge(verifier);
			String redirectUri = getRedirectUri(ctx);
			oauth.storePendingAuth(state, provider, verifier, redirectUri);
			String url = oauth.buildAuthorizationUrl(provider, redirectUri, state, challenge);

			buttons[i] = div(
				a(button("Sign in with " + provider.displayName())
					.withClass("outline")
					.withStyle("width: 100%; margin-bottom: 0.5em;")
				).withHref(url)
			);
		}

		returnPage(ctx, "Sign In",
			div(
				h3("Sign in to Convex"),
				p("Choose a provider to authenticate:"),
				div(buttons).withStyle("max-width: 400px; margin: 2em auto;")
			)
		);
	}

	/**
	 * GET /auth/callback — handles OAuth redirect from provider.
	 */
	private void handleCallback(Context ctx) {
		String error = ctx.queryParam("error");
		if (error != null) {
			String desc = ctx.queryParam("error_description");
			returnPage(ctx, "Authentication Failed",
				p("Provider returned an error: " + error),
				(desc != null) ? p(small(desc)) : text(""));
			return;
		}

		String code = ctx.queryParam("code");
		String state = ctx.queryParam("state");

		if (code == null || state == null) {
			returnPage(ctx, "Authentication Failed",
				p("Missing authorization code or state parameter."));
			return;
		}

		OAuthService oauth = restServer.getOAuthService();
		if (oauth == null) {
			returnPage(ctx, "Authentication Failed",
				p("Authentication service is not available."));
			return;
		}

		PendingAuth pending = oauth.consumeState(state);
		if (pending == null) {
			returnPage(ctx, "Invalid Request",
				p("Invalid or expired authentication state. Please try signing in again."));
			return;
		}

		String hostname = getHostname(ctx);
		AString identity = oauth.authenticate(pending.provider(), code,
			pending.codeVerifier(), pending.redirectUri(), hostname);

		if (identity == null) {
			returnPage(ctx, "Authentication Failed",
				p("Could not verify identity with the provider. Please try again."));
			return;
		}

		// Issue peer-signed JWT
		PeerAuth peerAuth = restServer.getAuthMiddleware().getPeerAuth();
		long expiry = 86400; // 24 hours
		AString peerToken = peerAuth.issuePeerToken(identity, expiry);

		returnPage(ctx, "Authenticated",
			div(
				h3("Authentication Successful"),
				p("Identity:"),
				preCode(identity.toString()),
				p("Your bearer token (valid 24 hours):"),
				pre(code(peerToken.toString()))
					.withStyle("word-break: break-all; white-space: pre-wrap;"),
				p(small("Copy this token and use it as a Bearer token in API requests."))
			).withStyle("max-width: 600px; margin: 2em auto;")
		);
	}

	/**
	 * Get the redirect URI for the OAuth callback.
	 */
	private String getRedirectUri(Context ctx) {
		String baseUrl = restServer.getBaseURL();
		if (baseUrl != null) {
			return baseUrl + "/auth/callback";
		}
		// Derive from request
		String proto = ctx.header("X-Forwarded-Proto");
		if (proto == null) proto = ctx.scheme();
		String host = ctx.header("X-Forwarded-Host");
		if (host == null) host = ctx.host();
		return proto + "://" + host + "/auth/callback";
	}
}
