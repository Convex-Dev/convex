package convex.cli.account;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import convex.api.Convex;
import convex.cli.ACommand;
import convex.cli.CLIError;
import convex.cli.ExitCodes;
import convex.cli.Main;
import convex.cli.mixins.AddressMixin;
import convex.cli.mixins.RemotePeerMixin;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.ParentCommand;

public abstract class AAccountCommand extends ACommand {

	/**
	 * Default port for the peer REST API (faucet etc.)
	 * TODO: make configurable, see #627
	 */
	protected static final int DEFAULT_REST_PORT = 8080;

	protected static final Duration REST_TIMEOUT = Duration.ofSeconds(30);

	@ParentCommand
	Account accountParent;

	@Mixin
	protected RemotePeerMixin peerMixin;

	@Mixin
	protected AddressMixin addressMixin;

	protected Convex connect() {
		return peerMixin.connect();
	}

	/**
	 * Gets the base URL for the peer REST API, derived from --host.
	 *
	 * Supports an optional scheme and port in the host value:
	 * - "peer.example.com"              = http://peer.example.com:8080 (conventional REST port)
	 * - "peer.example.com:9000"         = http://peer.example.com:9000
	 * - "https://peer.example.com"      = https://peer.example.com (scheme default port)
	 * - "https://peer.example.com:9000" = https://peer.example.com:9000
	 *
	 * @return Base URL for REST API, without trailing slash
	 */
	protected String getRestAPIBase() {
		String h = peerMixin.getHostname();
		while (h.endsWith("/")) h=h.substring(0,h.length()-1);

		String lower=h.toLowerCase();
		if (lower.startsWith("http://")||lower.startsWith("https://")) {
			// Explicit scheme: use as given, scheme default port applies if none specified
			return h;
		}

		// No scheme: default to http, using an explicit port if given
		int close=h.indexOf(']'); // allow for bracketed IPv6 literals
		int colon=h.lastIndexOf(':');
		if ((colon>close)&&(colon<h.length()-1)&&h.substring(colon+1).chars().allMatch(Character::isDigit)) {
			return "http://"+h;
		}
		return "http://"+h+":"+DEFAULT_REST_PORT;
	}

	/**
	 * POSTs a JSON request to a peer REST API endpoint
	 * @param apiUrl Full URL of the REST endpoint
	 * @param json JSON request body
	 * @return HTTP response with a String body
	 */
	protected HttpResponse<String> postJSON(String apiUrl, String json) throws InterruptedException {
		try {
			HttpClient client = HttpClient.newBuilder()
				.connectTimeout(REST_TIMEOUT)
				.build();

			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(apiUrl))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json))
				.timeout(REST_TIMEOUT)
				.build();

			return client.send(request, HttpResponse.BodyHandlers.ofString());
		} catch (ConnectException e) {
			throw new CLIError(ExitCodes.NOHOST,
				"Cannot connect to REST API at " + apiUrl + ". Check if the peer has its REST API enabled.", e);
		} catch (IOException e) {
			throw new CLIError(ExitCodes.TEMPFAIL, "REST API request failed: " + e.getMessage(), e);
		}
	}

	@Override
	public Main cli() {
		return accountParent.cli();
	}
}
