package convex.restapi.api;

import convex.api.ContentTypes;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.restapi.RESTServer;
import convex.restapi.handler.ConcurrentLimit;
import convex.restapi.handler.X402Gate;
import convex.x402.Fields;
import convex.x402.NetworkId;
import convex.x402.facilitator.ConvexFacilitator;
import convex.x402.model.PaymentPayload;
import convex.x402.model.PaymentRequirements;
import convex.x402.model.SettlementResponse;
import convex.x402.model.VerifyResponse;
import io.javalin.config.RoutesConfig;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;

/**
 * x402 payment protocol support (CAD042): facilitator endpoints plus the
 * payment gate for operator-configured paid routes.
 *
 * <p>The peer acts as its own facilitator: verification is a read against
 * local consensus state and settlement is an ordinary transaction submission.
 * The facilitator never signs anything and holds no funds, so these endpoints
 * are safe on public peers and follow the REST API's public-by-default
 * posture.</p>
 */
public class X402API extends ABaseAPI {

	/** Base route for facilitator endpoints */
	public static final String ROUTE = "/x402/";

	protected final ConvexFacilitator facilitator;

	private final ConcurrentLimit settleLimit = new ConcurrentLimit(4);
	// Verification is read-only but does signature checks and state reads per
	// call, so it gets its own cap beneath the global admission control
	private final ConcurrentLimit verifyLimit = new ConcurrentLimit(8);

	public X402API(RESTServer restServer) {
		super(restServer);
		NetworkId networkId = NetworkId.create(server.getPeer().getNetworkID());
		this.facilitator = new ConvexFacilitator(restServer.getConvex(), networkId,
				hash -> server.getPeer().getTransaction(hash) != null);
	}

	public ConvexFacilitator getFacilitator() {
		return facilitator;
	}

	@Override
	public void addRoutes(RoutesConfig routes) {
		if (restServer.getRESTConfig().isX402FacilitatorEnabled()) {
			routes.post(ROUTE + "verify", verifyLimit.handler(this::verify));
			routes.post(ROUTE + "settle", settleLimit.handler(this::settle));
			routes.get(ROUTE + "supported", this::supported);
		}
		X402Gate gate = new X402Gate(facilitator);
		gate.installFromConfig(routes, restServer.getRESTConfig());
	}

	/** Parsed body of a facilitator verify/settle request. */
	protected record FacilitatorRequest(PaymentPayload payload, PaymentRequirements requirements) {}

	protected FacilitatorRequest parseFacilitatorRequest(Context ctx) {
		AMap<AString, ACell> body = readJSONBody(ctx);
		ACell rawPayload = body.get(Fields.PAYMENT_PAYLOAD);
		if (rawPayload == null) {
			throw new BadRequestResponse("Request body requires a 'paymentPayload' object");
		}
		ACell rawRequirements = body.get(Fields.PAYMENT_REQUIREMENTS);
		if (rawRequirements == null) {
			throw new BadRequestResponse("Request body requires a 'paymentRequirements' object");
		}
		try {
			PaymentPayload payload = PaymentPayload.fromJSON(RT.castMap(rawPayload));
			PaymentRequirements requirements = PaymentRequirements.fromJSON(RT.castMap(rawRequirements));
			return new FacilitatorRequest(payload, requirements);
		} catch (IllegalArgumentException e) {
			throw new BadRequestResponse(e.getMessage());
		}
	}

	private void respondJSON(Context ctx, ACell json) {
		ctx.status(200);
		ctx.contentType(ContentTypes.JSON);
		ctx.result(JSON.toString(json));
	}

	@OpenApi(path = ROUTE + "verify",
			methods = HttpMethod.POST,
			operationId = "x402Verify",
			tags = {"x402"},
			summary = "Verify an x402 payment against current consensus state without settling it.",
			requestBody = @OpenApiRequestBody(
					description = "x402 verification request: {x402Version, paymentPayload, paymentRequirements}",
					content = {@OpenApiContent(type = "application/json")}),
			responses = {
					@OpenApiResponse(status = "200",
							description = "Verification outcome (isValid, invalidReason, payer)",
							content = {@OpenApiContent(type = "application/json")}),
					@OpenApiResponse(status = "400", description = "Malformed request body")})
	public void verify(Context ctx) throws InterruptedException {
		FacilitatorRequest req = parseFacilitatorRequest(ctx);
		VerifyResponse response = facilitator.verify(req.payload(), req.requirements());
		respondJSON(ctx, response.toJSON());
	}

	@OpenApi(path = ROUTE + "settle",
			methods = HttpMethod.POST,
			operationId = "x402Settle",
			tags = {"x402"},
			summary = "Settle an x402 payment by submitting its signed transaction for consensus.",
			requestBody = @OpenApiRequestBody(
					description = "x402 settlement request: {x402Version, paymentPayload, paymentRequirements}",
					content = {@OpenApiContent(type = "application/json")}),
			responses = {
					@OpenApiResponse(status = "200",
							description = "Settlement outcome (success, transaction, network, payer, amount, errorReason)",
							content = {@OpenApiContent(type = "application/json")}),
					@OpenApiResponse(status = "400", description = "Malformed request body")})
	public void settle(Context ctx) throws InterruptedException {
		FacilitatorRequest req = parseFacilitatorRequest(ctx);
		SettlementResponse response = facilitator.settle(req.payload(), req.requirements());
		respondJSON(ctx, response.toJSON());
	}

	@OpenApi(path = ROUTE + "supported",
			methods = HttpMethod.GET,
			operationId = "x402Supported",
			tags = {"x402"},
			summary = "List x402 payment kinds this facilitator supports.",
			responses = {
					@OpenApiResponse(status = "200",
							description = "Supported payment kinds (kinds, extensions, signers)",
							content = {@OpenApiContent(type = "application/json")})})
	public void supported(Context ctx) {
		respondJSON(ctx, facilitator.supported());
	}
}
