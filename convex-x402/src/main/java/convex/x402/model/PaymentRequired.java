package convex.x402.model;

import java.util.ArrayList;
import java.util.List;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.x402.Fields;
import convex.x402.X402;

/**
 * x402 v2 PaymentRequired: the payment demand delivered with a 402 response.
 *
 * @param x402Version Protocol version (2)
 * @param error Human-readable reason payment is (still) required, may be null
 * @param resource The protected resource
 * @param accepts Acceptable payment requirements
 */
public record PaymentRequired(int x402Version, String error, ResourceInfo resource,
		List<PaymentRequirements> accepts) {

	public static PaymentRequired create(ResourceInfo resource, PaymentRequirements... accepts) {
		return new PaymentRequired(X402.VERSION, null, resource, List.of(accepts));
	}

	public AMap<AString, ACell> toJSON() {
		AVector<ACell> acc = Vectors.empty();
		for (PaymentRequirements pr : accepts) {
			acc = acc.conj(pr.toJSON());
		}
		AMap<AString, ACell> m = Maps.of(
				Fields.X402_VERSION, CVMLong.create(x402Version),
				Fields.ACCEPTS, acc);
		if (resource != null) m = m.assoc(Fields.RESOURCE, resource.toJSON());
		if (error != null) m = m.assoc(Fields.ERROR, Strings.create(error));
		return m;
	}

	public static PaymentRequired fromJSON(AMap<AString, ACell> json) {
		if (json == null) throw new IllegalArgumentException("Missing PaymentRequired");
		int version = (int) Model.optLong(json, Fields.X402_VERSION, -1);
		AVector<ACell> acc = RT.ensureVector(json.get(Fields.ACCEPTS));
		if (acc == null) throw new IllegalArgumentException("PaymentRequired requires an accepts array");
		List<PaymentRequirements> accepts = new ArrayList<>();
		for (long i = 0; i < acc.count(); i++) {
			accepts.add(PaymentRequirements.fromJSON(RT.castMap(acc.get(i))));
		}
		ACell resource = json.get(Fields.RESOURCE);
		return new PaymentRequired(version,
				Model.optString(json, Fields.ERROR),
				(resource == null) ? null : ResourceInfo.fromJSON(RT.castMap(resource)),
				accepts);
	}
}
