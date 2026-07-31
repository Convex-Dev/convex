package convex.x402.facilitator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.x402.ErrorReasons;
import convex.x402.Fields;
import convex.x402.X402;
import convex.x402.model.PaymentPayload;
import convex.x402.model.PaymentRequirements;
import convex.x402.model.SettlementResponse;
import convex.x402.model.VerifyResponse;

/**
 * Client for any spec-compliant external x402 facilitator, reached over HTTP
 * via the standard {@code /verify}, {@code /settle} and {@code /supported}
 * endpoints.
 *
 * <p>This is how a Convex-side resource server accepts payment kinds it
 * cannot settle itself — e.g. the exact scheme with USDC on Base
 * ({@code eip155:8453}) via a hosted facilitator. The remote facilitator owns
 * all foreign-chain verification and settlement; this class only speaks the
 * facilitator envelope.</p>
 *
 * <p>Trust: a resource server using a remote facilitator trusts it to verify
 * and settle honestly — the standard x402 posture (see CAD042 security
 * considerations).</p>
 *
 * <p>Payment kinds may be configured explicitly with {@link #withKind}, or
 * discovered lazily from the facilitator's {@code /supported} endpoint.
 * {@link #handles} fails closed when support cannot be determined. Failures
 * of verify/settle calls are reported in the returned responses, never
 * thrown.</p>
 */
public class RemoteFacilitator implements Facilitator {
	private static final Logger log = LoggerFactory.getLogger(RemoteFacilitator.class);

	/** Request timeout for verify and supported calls */
	public static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(15);

	/** Slack added to the requirement's timeout for settle calls */
	public static final Duration SETTLE_SLACK = Duration.ofSeconds(15);

	/** Ceiling on the settle request timeout regardless of requirements */
	public static final Duration MAX_SETTLE_TIMEOUT = Duration.ofSeconds(120);

	/** A payment kind: a scheme on a network. */
	public record Kind(String scheme, String network) {}

	protected final HttpClient http;
	protected final String baseUrl;
	protected final UnaryOperator<HttpRequest.Builder> decorator;
	protected final List<Kind> configuredKinds;
	private volatile List<Kind> fetchedKinds;

	protected RemoteFacilitator(HttpClient http, String baseUrl,
			UnaryOperator<HttpRequest.Builder> decorator, List<Kind> configuredKinds) {
		this.http = http;
		this.baseUrl = baseUrl;
		this.decorator = decorator;
		this.configuredKinds = configuredKinds;
	}

	/**
	 * Creates a client for a remote facilitator.
	 *
	 * @param http HTTP client to use
	 * @param baseUrl Facilitator base URL, e.g. {@code https://x402.org/facilitator}
	 * @return New remote facilitator client
	 */
	public static RemoteFacilitator create(HttpClient http, URI baseUrl) {
		if (baseUrl == null) throw new IllegalArgumentException("Facilitator base URL required");
		String base = baseUrl.toString();
		if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
		return new RemoteFacilitator(http, base, UnaryOperator.identity(), List.of());
	}

	/**
	 * Returns a copy whose requests pass through the given decorator, e.g. to
	 * add authentication headers required by hosted facilitators.
	 *
	 * @param decorator Applied to every outgoing request builder
	 * @return Decorated remote facilitator client
	 */
	public RemoteFacilitator withDecorator(UnaryOperator<HttpRequest.Builder> decorator) {
		if (decorator == null) throw new IllegalArgumentException("Decorator required");
		return new RemoteFacilitator(http, baseUrl, decorator, configuredKinds);
	}

	/**
	 * Returns a copy that handles the given payment kind explicitly, instead of
	 * (or in addition to) kinds discovered from {@code /supported}.
	 *
	 * @param scheme Payment scheme, e.g. "exact"
	 * @param network CAIP-2 network, e.g. "eip155:8453"
	 * @return Remote facilitator client handling the kind
	 */
	public RemoteFacilitator withKind(String scheme, String network) {
		List<Kind> kinds = new ArrayList<>(configuredKinds);
		kinds.add(new Kind(scheme, network));
		return new RemoteFacilitator(http, baseUrl, decorator, List.copyOf(kinds));
	}

	@Override
	public VerifyResponse verify(PaymentPayload payload, PaymentRequirements requirements)
			throws InterruptedException {
		try {
			AMap<AString, ACell> response = post("/verify", facilitatorBody(payload, requirements),
					VERIFY_TIMEOUT);
			return VerifyResponse.fromJSON(response);
		} catch (IOException | RuntimeException e) {
			log.warn("Remote facilitator verify failed at {}: {}", baseUrl, e.toString());
			return VerifyResponse.invalid(ErrorReasons.UNEXPECTED_VERIFY_ERROR, null);
		}
	}

	@Override
	public SettlementResponse settle(PaymentPayload payload, PaymentRequirements requirements)
			throws InterruptedException {
		try {
			Duration timeout = Duration.ofSeconds(Math.max(1, requirements.maxTimeoutSeconds()))
					.plus(SETTLE_SLACK);
			if (timeout.compareTo(MAX_SETTLE_TIMEOUT) > 0) timeout = MAX_SETTLE_TIMEOUT;
			AMap<AString, ACell> response = post("/settle", facilitatorBody(payload, requirements),
					timeout);
			return SettlementResponse.fromJSON(response);
		} catch (IOException | RuntimeException e) {
			log.warn("Remote facilitator settle failed at {}: {}", baseUrl, e.toString());
			return SettlementResponse.failure(ErrorReasons.UNEXPECTED_SETTLE_ERROR, null,
					requirements.network());
		}
	}

	@Override
	public boolean handles(String scheme, String network) {
		if ((scheme == null) || (network == null)) return false;
		for (Kind kind : configuredKinds) {
			if (kind.scheme().equals(scheme) && kind.network().equals(network)) return true;
		}
		if (!configuredKinds.isEmpty()) return false;
		List<Kind> kinds = fetchedKinds;
		if (kinds == null) {
			kinds = fetchKinds(); // fail closed: empty on failure, retried next call
		}
		for (Kind kind : kinds) {
			if (kind.scheme().equals(scheme) && kind.network().equals(network)) return true;
		}
		return false;
	}

	@Override
	public AMap<AString, ACell> supported() throws InterruptedException {
		try {
			HttpRequest request = decorator.apply(HttpRequest.newBuilder(URI.create(baseUrl + "/supported"))
					.timeout(VERIFY_TIMEOUT)
					.header("Accept", "application/json")
					.GET()).build();
			return send(request);
		} catch (IOException | RuntimeException e) {
			throw new IllegalStateException("Remote facilitator /supported failed at " + baseUrl, e);
		}
	}

	private List<Kind> fetchKinds() {
		try {
			AMap<AString, ACell> supported = supported();
			List<Kind> kinds = new ArrayList<>();
			AVector<ACell> vector = RT.ensureVector(supported.get(Fields.KINDS));
			if (vector != null) {
				for (long i = 0; i < vector.count(); i++) {
					AMap<AString, ACell> entry = RT.castMap(vector.get(i));
					if (entry == null) continue;
					AString scheme = RT.ensureString(entry.get(Fields.SCHEME));
					AString network = RT.ensureString(entry.get(Fields.NETWORK));
					if ((scheme != null) && (network != null)) {
						kinds.add(new Kind(scheme.toString(), network.toString()));
					}
				}
			}
			fetchedKinds = List.copyOf(kinds);
			return fetchedKinds;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return List.of();
		} catch (RuntimeException e) {
			log.warn("Could not discover kinds from {}: {}", baseUrl, e.toString());
			return List.of();
		}
	}

	private String facilitatorBody(PaymentPayload payload, PaymentRequirements requirements) {
		return JSON.toString(Maps.of(
				Fields.X402_VERSION, CVMLong.create(X402.VERSION),
				Fields.PAYMENT_PAYLOAD, payload.toJSON(),
				Fields.PAYMENT_REQUIREMENTS, requirements.toJSON()));
	}

	private AMap<AString, ACell> post(String path, String body, Duration timeout)
			throws IOException, InterruptedException {
		HttpRequest request = decorator.apply(HttpRequest.newBuilder(URI.create(baseUrl + path))
				.timeout(timeout)
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))).build();
		return send(request);
	}

	private AMap<AString, ACell> send(HttpRequest request) throws IOException, InterruptedException {
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IOException("Facilitator returned HTTP " + response.statusCode());
		}
		AMap<AString, ACell> map = RT.castMap(JSON.parse(response.body()));
		if (map == null) throw new IOException("Facilitator response is not a JSON object");
		return map;
	}

	@Override
	public String toString() {
		return "RemoteFacilitator(" + baseUrl + ")";
	}
}
