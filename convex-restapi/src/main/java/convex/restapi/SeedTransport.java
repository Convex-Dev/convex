package convex.restapi;

import java.net.InetAddress;
import java.net.UnknownHostException;

import io.javalin.http.Context;

/** Shared transport policy for requests that receive or return private seeds. */
public final class SeedTransport {
	private SeedTransport() {}

	/**
	 * Tests whether a request is safe for private seed material.
	 * HTTPS and TLS-terminating proxies are accepted. Loopback HTTP is exempt so
	 * local development and tests do not require TLS.
	 */
	public static boolean isSecure(Context ctx) {
		return isSecure(ctx.scheme(),ctx.header("X-Forwarded-Proto"),ctx.req().getRemoteAddr());
	}

	/** Pure transport-security decision, exposed separately for unit tests. */
	public static boolean isSecure(String scheme, String forwardedProto, String remoteAddr) {
		if ("https".equalsIgnoreCase(scheme)) return true;
		if (forwardedProto!=null) {
			// May be a comma-separated list from chained proxies; first is the client-facing hop.
			String first=forwardedProto.split(",")[0].trim();
			// An explicit cleartext client-facing hop must not inherit a loopback exemption
			// merely because a reverse proxy connects to this server locally.
			return "https".equalsIgnoreCase(first);
		}
		if (remoteAddr!=null) {
			try {
				// Javalin supplies the numeric remote address, so this does not normally perform DNS.
				if (InetAddress.getByName(remoteAddr).isLoopbackAddress()) return true;
			} catch (UnknownHostException e) {
				// Unknown remote address: not exempt.
			}
		}
		return false;
	}

	/** Message for a request that has already carried sensitive key material. */
	public static String rejectedIncomingMessage() {
		return "Seed operations require HTTPS: refusing cleartext HTTP. Sensitive key material sent in this request "
				+"may already be compromised; key rotation is suggested. Use an HTTPS endpoint, or set "
				+"rest.allowHttpSeeds=true in the peer config for a trusted development or test network.";
	}

	/** Message used before a newly generated seed would be disclosed. */
	public static String rejectedOutputMessage() {
		return "Seed operations require HTTPS: refusing to return an Ed25519 seed over cleartext HTTP. "
				+"Use an HTTPS endpoint, or set rest.allowHttpSeeds=true in the peer config for a trusted development or test network.";
	}
}
