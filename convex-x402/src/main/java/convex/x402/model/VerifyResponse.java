package convex.x402.model;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import convex.x402.Fields;
import convex.x402.PaymentError;

/**
 * x402 v2 VerifyResponse: the outcome of payment verification.
 *
 * @param isValid Whether the payment authorisation is valid
 * @param invalidReason Machine-readable reason code, null when valid
 * @param payer Payer's Convex address, may be null when undeterminable
 * @param detail Human-readable diagnostic for the failure, may be null.
 *        Additive to the upstream schema; reason codes stay exact in
 *        invalidReason.
 */
public record VerifyResponse(boolean isValid, String invalidReason, String payer, String detail) {

	public static VerifyResponse valid(String payer) {
		return new VerifyResponse(true, null, payer, null);
	}

	public static VerifyResponse invalid(String reason, String payer) {
		return new VerifyResponse(false, reason, payer, null);
	}

	public static VerifyResponse invalid(PaymentError error, String payer) {
		return new VerifyResponse(false, error.reason(), payer, error.detail());
	}

	public AMap<AString, ACell> toJSON() {
		AMap<AString, ACell> m = Maps.of(Fields.IS_VALID, CVMBool.create(isValid));
		if (invalidReason != null) m = m.assoc(Fields.INVALID_REASON, Strings.create(invalidReason));
		if (payer != null) m = m.assoc(Fields.PAYER, Strings.create(payer));
		if (detail != null) m = m.assoc(Fields.DETAIL, Strings.create(detail));
		return m;
	}

	public static VerifyResponse fromJSON(AMap<AString, ACell> json) {
		if (json == null) throw new IllegalArgumentException("Missing VerifyResponse");
		ACell valid = json.get(Fields.IS_VALID);
		if (valid == null) throw new IllegalArgumentException("VerifyResponse requires isValid");
		return new VerifyResponse(RT.bool(valid),
				Model.optString(json, Fields.INVALID_REASON),
				Model.optString(json, Fields.PAYER),
				Model.optString(json, Fields.DETAIL));
	}
}
