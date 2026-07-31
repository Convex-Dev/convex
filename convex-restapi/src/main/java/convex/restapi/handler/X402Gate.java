package convex.restapi.handler;

import java.util.ArrayList;
import java.util.List;

import convex.api.ContentTypes;
import convex.core.cvm.Address;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.CAIP;
import convex.core.util.JSON;
import convex.restapi.RESTConfig;
import convex.x402.ErrorReasons;
import convex.x402.Fields;
import convex.x402.X402;
import convex.x402.facilitator.Facilitator;
import convex.x402.model.PaymentPayload;
import convex.x402.model.PaymentRequired;
import convex.x402.model.PaymentRequirements;
import convex.x402.model.ResourceInfo;
import convex.x402.model.SettlementResponse;
import convex.x402.scheme.ExactConvexScheme;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;

/**
 * Payment gate for x402-protected routes (CAD042).
 *
 * <p>Installed as a Javalin before-handler on each configured path. A request
 * without a valid payment receives a 402 with a {@code PAYMENT-REQUIRED}
 * header (mirrored in the body for debuggability); a request carrying a valid
 * {@code PAYMENT-SIGNATURE} is settled before the resource is served — the
 * exact scheme requires settle-before-serve, since a merely verified payment
 * could still be spent elsewhere first.</p>
 */
public class X402Gate {

	private static final AString PATH = Strings.intern("path");

	/** A configured paid route. */
	public record GatedRoute(String path, PaymentRequirements requirements, String description) {}

	protected final Facilitator facilitator;
	protected final List<GatedRoute> gatedRoutes;

	public X402Gate(Facilitator facilitator, List<GatedRoute> gatedRoutes) {
		this.facilitator = facilitator;
		this.gatedRoutes = gatedRoutes;
	}

	/**
	 * Builds a gate from the {@code rest.x402} configuration section.
	 *
	 * @param facilitator Facilitator used to settle payments
	 * @param config REST configuration
	 * @return Gate for the configured routes (possibly none)
	 * @throws IllegalArgumentException if the route configuration is invalid
	 */
	public static X402Gate fromConfig(Facilitator facilitator, RESTConfig config) {
		List<GatedRoute> gated = new ArrayList<>();
		AString defaultPayTo = config.getX402PayTo();
		AVector<ACell> entries = config.getX402Routes();
		String network = facilitator.getNetworkId().canonical();
		for (long i = 0; i < entries.count(); i++) {
			AMap<AString, ACell> entry = RT.castMap(entries.get(i));
			if (entry == null) {
				throw new IllegalArgumentException("rest.x402.routes entries must be objects");
			}
			String path = getString(entry, PATH);
			if (path == null) {
				throw new IllegalArgumentException("rest.x402.routes entries require a path");
			}
			String amount = getAmount(entry);
			if (amount == null) {
				throw new IllegalArgumentException("rest.x402.routes entries require an amount in atomic units");
			}
			String asset = getString(entry, Fields.ASSET);
			if (asset == null) asset = CAIP.CONVEX_ASSET_ID.toString();
			String payTo = getString(entry, Fields.PAY_TO);
			if (payTo == null) payTo = (defaultPayTo == null) ? null : defaultPayTo.toString();
			if (payTo == null) {
				throw new IllegalArgumentException("rest.x402.routes entries require a payTo (or set rest.x402.payTo)");
			}
			long timeout = getTimeout(entry);
			String description = getString(entry, Fields.DESCRIPTION);

			PaymentRequirements requirements = new PaymentRequirements(X402.SCHEME_EXACT, network,
					amount, asset, payTo, timeout, null);
			validate(requirements, path);
			gated.add(new GatedRoute(path, requirements, description));
		}
		return new X402Gate(facilitator, gated);
	}

	/** Fails fast at configuration time if a route's requirements are unpayable. */
	private static void validate(PaymentRequirements requirements, String path) {
		Address payTo = Address.parse(requirements.payTo());
		if (payTo == null) {
			throw new IllegalArgumentException("Invalid payTo address for x402 route " + path);
		}
		if (ExactConvexScheme.expectedTransaction(Address.ZERO, 1, payTo, requirements) == null) {
			throw new IllegalArgumentException("Invalid amount or asset for x402 route " + path);
		}
	}

	private static String getString(AMap<AString, ACell> entry, AString key) {
		AString v = RT.ensureString(entry.get(key));
		return (v == null) ? null : v.toString();
	}

	private static String getAmount(AMap<AString, ACell> entry) {
		ACell raw = entry.get(Fields.AMOUNT);
		if (raw == null) return null;
		AString s = RT.ensureString(raw);
		if (s != null) return s.toString();
		CVMLong v = RT.ensureLong(raw);
		return (v == null) ? null : Long.toString(v.longValue());
	}

	private static long getTimeout(AMap<AString, ACell> entry) {
		CVMLong v = RT.ensureLong(entry.get(Fields.MAX_TIMEOUT_SECONDS));
		return (v == null) ? PaymentRequirements.DEFAULT_TIMEOUT_SECONDS : v.longValue();
	}

	/**
	 * Installs the gate's before-handlers on the configured paths.
	 * @param routes Routes configuration to install on
	 */
	public void install(RoutesConfig routes) {
		for (GatedRoute gatedRoute : gatedRoutes) {
			routes.before(gatedRoute.path(), ctx -> handle(ctx, gatedRoute));
		}
	}

	protected void handle(Context ctx, GatedRoute route) throws InterruptedException {
		if (ctx.method() == HandlerType.OPTIONS) return; // CORS preflight is never gated

		String header = ctx.header(X402.HEADER_PAYMENT_SIGNATURE);
		if (header == null) {
			respondPaymentRequired(ctx, route, null);
			return;
		}

		final PaymentPayload payment;
		try {
			payment = PaymentPayload.fromJSON(X402.decodeHeader(header));
		} catch (IllegalArgumentException e) {
			respondPaymentRequired(ctx, route, ErrorReasons.INVALID_PAYLOAD);
			return;
		}

		SettlementResponse settlement = facilitator.settle(payment, route.requirements());
		if (settlement.success()) {
			ctx.header(X402.HEADER_PAYMENT_RESPONSE, X402.encodeHeader(settlement.toJSON()));
			return; // fall through to the protected handler
		}
		respondPaymentRequired(ctx, route, settlement.errorReason());
	}

	protected void respondPaymentRequired(Context ctx, GatedRoute route, String error) {
		ResourceInfo resource = new ResourceInfo(ctx.fullUrl(), route.description(), null);
		PaymentRequired required = new PaymentRequired(X402.VERSION, error, resource,
				List.of(route.requirements()));
		ACell json = required.toJSON();
		ctx.status(402);
		ctx.header(X402.HEADER_PAYMENT_REQUIRED, X402.encodeHeader(json));
		ctx.contentType(ContentTypes.JSON);
		ctx.result(JSON.toString(json));
		ctx.skipRemainingHandlers();
	}
}
