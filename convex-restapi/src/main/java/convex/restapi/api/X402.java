package convex.restapi.api;

import convex.api.ContentTypes;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.restapi.RESTServer;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;

public class X402 extends ABaseAPI {

	private static final String ROUTE = "/verify";

	private static final AString FIELD_MAX_AMOUNT = Strings.create("maxAmountRequired");
	private static final AString FIELD_RESOURCE = Strings.create("resource");
	private static final AString FIELD_DESCRIPTION = Strings.create("description");
	private static final AString FIELD_PAY_TO = Strings.create("payTo");
	private static final AString FIELD_ASSET = Strings.create("asset");
	private static final AString FIELD_NETWORK = Strings.create("network");
	private static final AString FIELD_IS_VALID = Strings.create("isValid");
	private static final AString FIELD_INVALID_REASON = Strings.create("invalidReason");
	private static final AString FIELD_PAYER = Strings.create("payer");

	public X402(RESTServer restServer) {
		super(restServer);
	}

	@Override
	public void addRoutes(Javalin app) {
		app.post(ROUTE, this::handleVerify);
	}

	public void respondPaymentRequired(Context ctx, AMap<AString, ACell> baseFields) {
		AMap<AString, ACell> payload = (baseFields == null) ? Maps.empty() : baseFields;
		payload = payload.assoc(FIELD_MAX_AMOUNT, Strings.create("0.10"));
		payload = payload.assoc(FIELD_RESOURCE, Strings.create("/api/market-data"));
		payload = payload.assoc(FIELD_DESCRIPTION,
			Strings.create("Access to real-time market data requires payment."));
		payload = payload.assoc(FIELD_PAY_TO, Strings.create("#13"));
		payload = payload.assoc(FIELD_ASSET, Strings.create("slip44:864"));
		payload = payload.assoc(FIELD_NETWORK, Strings.create("convex"));

		ctx.status(402);
		ctx.contentType(ContentTypes.JSON);
		ctx.result(JSON.toString(payload));
	}

	private void handleVerify(Context ctx) {
		AMap<AString, ACell> body = readJSONBody(ctx);
		AString payer = RT.ensureString(body.get(FIELD_PAYER));
		if (payer == null) {
			throw new BadRequestResponse("Missing 'payer' field");
		}

		boolean isValid = true;
		ACell isValidCell = body.get(FIELD_IS_VALID);
		if (isValidCell != null) {
			isValid = RT.bool(isValidCell);
		}
		AString invalidReason = RT.ensureString(body.get(FIELD_INVALID_REASON));
		if ((invalidReason != null) && (invalidReason.count() > 0)) {
			isValid = false;
		}
		if (!isValid && invalidReason == null) {
			invalidReason = Strings.create("insufficient_funds");
		}

		AMap<AString, ACell> response = Maps.of(
			FIELD_IS_VALID, isValid ? CVMBool.TRUE : CVMBool.FALSE,
			FIELD_PAYER, payer
		);
		if (!isValid && invalidReason != null) {
			response = response.assoc(FIELD_INVALID_REASON, invalidReason);
		}

		ctx.status(200);
		ctx.contentType(ContentTypes.JSON);
		ctx.result(JSON.toString(response));
	}

}
