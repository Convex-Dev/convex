package convex.x402.model;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.x402.Fields;

/**
 * x402 v2 PaymentPayload: a client's signed payment submission.
 *
 * @param x402Version Protocol version (2)
 * @param resource The resource being paid for, may be null
 * @param accepted The payment requirements the client chose to satisfy
 * @param payload Scheme-specific payment data; for the Convex exact scheme a
 *        JSON object with a "transaction" field containing the hex CAD3
 *        encoding of a signed transaction
 */
public record PaymentPayload(int x402Version, ResourceInfo resource,
		PaymentRequirements accepted, AMap<AString, ACell> payload) {

	public AMap<AString, ACell> toJSON() {
		AMap<AString, ACell> m = Maps.of(
				Fields.X402_VERSION, CVMLong.create(x402Version),
				Fields.ACCEPTED, accepted.toJSON(),
				Fields.PAYLOAD, payload);
		if (resource != null) m = m.assoc(Fields.RESOURCE, resource.toJSON());
		return m;
	}

	public static PaymentPayload fromJSON(AMap<AString, ACell> json) {
		if (json == null) throw new IllegalArgumentException("Missing PaymentPayload");
		int version = (int) Model.optLong(json, Fields.X402_VERSION, -1);
		ACell rawPayload = json.get(Fields.PAYLOAD);
		AMap<AString, ACell> payload = (rawPayload == null) ? null : RT.castMap(rawPayload);
		if (payload == null) throw new IllegalArgumentException("PaymentPayload requires a payload object");
		ACell resource = json.get(Fields.RESOURCE);
		ACell accepted = json.get(Fields.ACCEPTED);
		return new PaymentPayload(version,
				(resource == null) ? null : ResourceInfo.fromJSON(RT.castMap(resource)),
				PaymentRequirements.fromJSON((accepted == null) ? null : RT.castMap(accepted)),
				payload);
	}
}
