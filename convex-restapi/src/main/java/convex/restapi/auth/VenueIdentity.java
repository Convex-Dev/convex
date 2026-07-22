package convex.restapi.auth;

import java.net.URI;
import java.util.Locale;

import convex.core.data.AString;
import convex.core.data.Strings;

/** Canonical identity helpers for a REST venue. */
public final class VenueIdentity {

	private VenueIdentity() {}

	/**
	 * Derives the venue's root {@code did:web} identifier from its configured base URL.
	 *
	 * <p>Administration must use a configured value rather than request headers: Host
	 * and forwarding headers are controlled by, or on behalf of, the requester. This
	 * method accepts only an origin URL because this REST server publishes its venue
	 * document at {@code /.well-known/did.json}.</p>
	 *
	 * @param baseUrl externally visible HTTP(S) origin
	 * @return canonical venue DID
	 * @throws IllegalArgumentException if the URL is not a valid origin
	 */
	public static AString fromBaseUrl(String baseUrl) {
		if ((baseUrl==null)||baseUrl.isBlank()) throw new IllegalArgumentException("REST base URL is required");
		final URI uri;
		try {
			uri=URI.create(baseUrl);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Invalid REST base URL: "+baseUrl,e);
		}

		String scheme=uri.getScheme();
		String host=uri.getHost();
		String path=uri.getPath();
		if ((scheme==null)||(!scheme.equalsIgnoreCase("http")&&!scheme.equalsIgnoreCase("https"))
				||(host==null)||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null
				||((path!=null)&&!path.isEmpty()&&!"/".equals(path))) {
			throw new IllegalArgumentException("REST base URL must be an HTTP(S) origin without credentials, path, query or fragment: "+baseUrl);
		}

		host=host.toLowerCase(Locale.ROOT).replace(":","%3A");
		int port=uri.getPort();
		boolean defaultPort=(port<0)||(port==80&&scheme.equalsIgnoreCase("http"))
				||(port==443&&scheme.equalsIgnoreCase("https"));
		if (!defaultPort) host=host+"%3A"+port;
		return Strings.create("did:web:"+host);
	}
}
