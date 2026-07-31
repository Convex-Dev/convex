package convex.x402.model;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import convex.x402.Fields;

/**
 * x402 v2 VerifyResponse: the outcome of payment verification.
 *
 * @param isValid Whether the payment authorisation is valid
 * @param invalidReason Reason for invalidity, null when valid
 * @param payer Payer's Convex address, may be null when undeterminable
 */
public record VerifyResponse(boolean isValid, String invalidReason, String payer) {

	public static VerifyResponse valid(String payer) {
		return new VerifyResponse(true, null, payer);
	}

	public static VerifyResponse invalid(String reason, String payer) {
		return new VerifyResponse(false, reason, payer);
	}

	public AMap<AString, ACell> toJSON() {
		AMap<AString, ACell> m = Maps.of(Fields.IS_VALID, CVMBool.create(isValid));
		if (invalidReason != null) m = m.assoc(Fields.INVALID_REASON, Strings.create(invalidReason));
		if (payer != null) m = m.assoc(Fields.PAYER, Strings.create(payer));
		return m;
	}

	public static VerifyResponse fromJSON(AMap<AString, ACell> json) {
		if (json == null) throw new IllegalArgumentException("Missing VerifyResponse");
		ACell valid = json.get(Fields.IS_VALID);
		if (valid == null) throw new IllegalArgumentException("VerifyResponse requires isValid");
		return new VerifyResponse(RT.bool(valid),
				Model.optString(json, Fields.INVALID_REASON),
				Model.optString(json, Fields.PAYER));
	}
}
