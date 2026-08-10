package convex.x402.model;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.x402.Fields;

/**
 * x402 v2 PaymentRequirements: one acceptable way of paying for a resource.
 *
 * @param scheme Payment scheme identifier, e.g. "exact"
 * @param network CAIP-2 network identifier, e.g. "convex:protonet"
 * @param amount Required amount in atomic units, as a base-10 string
 * @param asset Asset identifier, e.g. "slip44:864" or "cad29:789"
 * @param payTo Recipient Convex address, e.g. "#13"
 * @param maxTimeoutSeconds Maximum time allowed for settlement
 * @param extra Scheme-specific extra data, may be null
 */
public record PaymentRequirements(String scheme, String network, String amount, String asset,
		String payTo, long maxTimeoutSeconds, AMap<AString, ACell> extra) {

	/** Default settlement timeout in seconds */
	public static final long DEFAULT_TIMEOUT_SECONDS = 30;

	public static PaymentRequirements create(String scheme, String network, String amount,
			String asset, String payTo) {
		return new PaymentRequirements(scheme, network, amount, asset, payTo,
				DEFAULT_TIMEOUT_SECONDS, null);
	}

	public AMap<AString, ACell> toJSON() {
		AMap<AString, ACell> m = Maps.of(
				Fields.SCHEME, Strings.create(scheme),
				Fields.NETWORK, Strings.create(network),
				Fields.AMOUNT, Strings.create(amount),
				Fields.ASSET, Strings.create(asset),
				Fields.PAY_TO, Strings.create(payTo),
				Fields.MAX_TIMEOUT_SECONDS, CVMLong.create(maxTimeoutSeconds));
		if (extra != null) m = m.assoc(Fields.EXTRA, extra);
		return m;
	}

	public static PaymentRequirements fromJSON(AMap<AString, ACell> json) {
		if (json == null) throw new IllegalArgumentException("Missing payment requirements");
		ACell extra = json.get(Fields.EXTRA);
		return new PaymentRequirements(
				Model.reqString(json, Fields.SCHEME),
				Model.reqString(json, Fields.NETWORK),
				Model.reqString(json, Fields.AMOUNT),
				Model.reqString(json, Fields.ASSET),
				Model.reqString(json, Fields.PAY_TO),
				Model.optLong(json, Fields.MAX_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS),
				(extra == null) ? null : RT.castMap(extra));
	}
}
