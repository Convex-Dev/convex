package convex.restapi.handler;

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
import convex.x402.facilitator.ConvexFacilitator;
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
 * <p>Installed as a Javalin before-handler on each protected path. Each
 * request is decided by an {@link X402Policy}: served freely, refused, or
 * charged — in which case a request without a valid payment receives a 402
 * with a {@code PAYMENT-REQUIRED} header (mirrored in the body for
 * debuggability), and a request carrying a valid {@code PAYMENT-SIGNATURE} is
 * settled before the resource is served. Settle-before-serve is mandatory for
 * the exact scheme: a merely verified payment could still be spent elsewhere
 * first.</p>
 *
 * <p>A gate combines one or more {@link Facilitator}s and routes each payment
 * to the first facilitator handling its (scheme, network). This lets a
 * resource server accept Convex payments directly and foreign kinds (e.g.
 * USDC on Base) via a {@link convex.x402.facilitator.RemoteFacilitator}:</p>
 *
 * <pre>{@code
 * ConvexFacilitator convexF = new ConvexFacilitator(convex, networkId, confirmedCheck);
 * Facilitator baseF = RemoteFacilitator.create(http, URI.create("https://x402.org/facilitator"))
 *         .withKind("exact", "eip155:8453");
 * X402Gate gate = new X402Gate(convexF, baseF);
 * gate.protect(routes, "/paid/*", myPolicy);   // policy offers both options via charge(List, desc)
 * }</pre>
 */
public class X402Gate {

	private static final AString PATH = Strings.intern("path");

	/** Context attribute holding the {@link SettlementResponse} for a paid request */
	public static final String ATTR_RECEIPT = "x402.receipt";

	/** Context attribute holding the payer address string for a paid request */
	public static final String ATTR_PAYER = "x402.payer";

	protected final List<Facilitator> facilitators;

	/**
	 * Creates a gate over one or more facilitators. Payments route to the first
	 * facilitator that handles their (scheme, network).
	 *
	 * @param facilitators Facilitators in routing priority order
	 */
	public X402Gate(Facilitator... facilitators) {
		if ((facilitators == null) || (facilitators.length == 0)) {
			throw new IllegalArgumentException("At least one facilitator required");
		}
		this.facilitators = List.of(facilitators);
	}

	public List<Facilitator> getFacilitators() {
		return facilitators;
	}

	/**
	 * Protects a path with a payment policy, installing a before-handler.
	 *
	 * @param routes Routes configuration to install on
	 * @param path Path to protect, may include wildcards e.g. {@code /paid/*}
	 * @param policy Policy deciding each request
	 */
	public void protect(RoutesConfig routes, String path, X402Policy policy) {
		if (policy == null) throw new IllegalArgumentException("Policy required");
		routes.before(path, ctx -> handle(ctx, policy));
	}

	/**
	 * Builds exact scheme payment requirements against this gate's Convex
	 * network, for use in policy decisions.
	 *
	 * @param amount Amount in atomic units as a base-10 string
	 * @param asset Asset identifier, e.g. "slip44:864" or "cad29:789"
	 * @param payTo Recipient address, e.g. "#13"
	 * @return Payment requirements for this gate's Convex facilitator network
	 * @throws IllegalStateException if the gate has no {@link ConvexFacilitator}
	 */
	public PaymentRequirements price(String amount, String asset, String payTo) {
		for (Facilitator f : facilitators) {
			if (f instanceof ConvexFacilitator cf) {
				return new PaymentRequirements(X402.SCHEME_EXACT, cf.getNetworkId().canonical(),
						amount, asset, payTo, PaymentRequirements.DEFAULT_TIMEOUT_SECONDS, null);
			}
		}
		throw new IllegalStateException("Gate has no Convex facilitator to price against");
	}

	/**
	 * The fixed-price-per-call policy: every request is charged the same
	 * requirements, and replayed settled payments are re-served without a
	 * second charge (idempotent retry semantics).
	 *
	 * @param requirements Requirements charged for every request
	 * @param description Description of the resource, may be null
	 * @return Fixed-price policy
	 */
	public static X402Policy fixedPrice(PaymentRequirements requirements, String description) {
		if (requirements == null) throw new IllegalArgumentException("Payment requirements required");
		X402Decision decision = X402Decision.charge(requirements, description);
		return ctx -> decision;
	}

	/**
	 * Installs fixed-price gates for the routes configured in the
	 * {@code rest.x402} section.
	 *
	 * @param routes Routes configuration to install on
	 * @param config REST configuration
	 * @throws IllegalArgumentException if the route configuration is invalid
	 */
	public void installFromConfig(RoutesConfig routes, RESTConfig config) {
		AString defaultPayTo = config.getX402PayTo();
		AVector<ACell> entries = config.getX402Routes();
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

			PaymentRequirements requirements = new PaymentRequirements(X402.SCHEME_EXACT,
					price(amount, asset, payTo).network(), amount, asset, payTo, timeout, null);
			validate(requirements, path);
			protect(routes, path, fixedPrice(requirements, description));
		}
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

	protected void handle(Context ctx, X402Policy policy) throws InterruptedException {
		if (ctx.method() == HandlerType.OPTIONS) return; // CORS preflight is never gated

		X402Decision decision = policy.decide(ctx);
		if ((decision == null) || (decision.kind() == X402Decision.Kind.ALLOW)) return;
		if (decision.kind() == X402Decision.Kind.DENY) {
			ctx.status(decision.status());
			ctx.result((decision.message() == null) ? "Refused" : decision.message());
			ctx.skipRemainingHandlers();
			return;
		}

		List<PaymentRequirements> offered = decision.requirements();
		String header = ctx.header(X402.HEADER_PAYMENT_SIGNATURE);
		if (header == null) {
			respondPaymentRequired(ctx, offered, decision.description(), null);
			return;
		}

		final PaymentPayload payment;
		try {
			payment = PaymentPayload.fromJSON(X402.decodeHeader(header));
		} catch (IllegalArgumentException e) {
			respondPaymentRequired(ctx, offered, decision.description(), ErrorReasons.INVALID_PAYLOAD);
			return;
		}

		// Settle against the server's own copy of the option the client chose:
		// the client's echo selects, but never defines, the requirements
		PaymentRequirements chosen = null;
		for (PaymentRequirements option : offered) {
			if (option.equals(payment.accepted())) {
				chosen = option;
				break;
			}
		}
		if (chosen == null) {
			respondPaymentRequired(ctx, offered, decision.description(),
					ErrorReasons.INVALID_PAYMENT_REQUIREMENTS);
			return;
		}

		Facilitator facilitator = route(chosen);
		if (facilitator == null) {
			respondPaymentRequired(ctx, offered, decision.description(),
					ErrorReasons.UNSUPPORTED_SCHEME);
			return;
		}

		SettlementResponse settlement = facilitator.settle(payment, chosen);
		if (!settlement.success()) {
			respondPaymentRequired(ctx, offered, decision.description(), settlement.errorReason());
			return;
		}

		ctx.attribute(ATTR_RECEIPT, settlement);
		ctx.attribute(ATTR_PAYER, settlement.payer());
		if (!policy.onSettled(ctx, payment, settlement)) {
			// Typically a replayed payment whose value was already consumed
			ctx.attribute(ATTR_RECEIPT, null);
			ctx.attribute(ATTR_PAYER, null);
			respondPaymentRequired(ctx, offered, decision.description(),
					ErrorReasons.INVALID_SEQUENCE);
			return;
		}
		ctx.header(X402.HEADER_PAYMENT_RESPONSE, X402.encodeHeader(settlement.toJSON()));
		// fall through to the protected handler
	}

	/** Finds the first facilitator handling the given requirements, or null. */
	protected Facilitator route(PaymentRequirements requirements) {
		for (Facilitator f : facilitators) {
			if (f.handles(requirements.scheme(), requirements.network())) return f;
		}
		return null;
	}

	/**
	 * Gets the settlement receipt for a request served after payment, or null
	 * if the request was not paid (e.g. allowed by policy).
	 *
	 * @param ctx Request context
	 * @return Settlement receipt, or null
	 */
	public static SettlementResponse getReceipt(Context ctx) {
		return ctx.attribute(ATTR_RECEIPT);
	}

	/**
	 * Gets the payer address for a request served after payment, or null if the
	 * request was not paid.
	 *
	 * @param ctx Request context
	 * @return Payer address string e.g. "#123", or null
	 */
	public static String getPayer(Context ctx) {
		return ctx.attribute(ATTR_PAYER);
	}

	protected void respondPaymentRequired(Context ctx, List<PaymentRequirements> offered,
			String description, String error) {
		ResourceInfo resource = new ResourceInfo(ctx.fullUrl(), description, null);
		PaymentRequired required = new PaymentRequired(X402.VERSION, error, resource, offered);
		ACell json = required.toJSON();
		ctx.status(402);
		ctx.header(X402.HEADER_PAYMENT_REQUIRED, X402.encodeHeader(json));
		ctx.contentType(ContentTypes.JSON);
		ctx.result(JSON.toString(json));
		ctx.skipRemainingHandlers();
	}
}
