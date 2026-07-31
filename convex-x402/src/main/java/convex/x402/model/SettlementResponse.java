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
 * x402 v2 SettlementResponse: the outcome of payment settlement.
 *
 * @param success Whether the payment settled on the network
 * @param errorReason Error reason when settlement failed, else null
 * @param payer Payer's Convex address, may be null when undeterminable
 * @param transaction Hex hash of the settled transaction, empty when unsettled
 * @param network CAIP-2 network identifier
 * @param amount Amount settled in atomic units, may be null when unsettled
 */
public record SettlementResponse(boolean success, String errorReason, String payer,
		String transaction, String network, String amount) {

	public static SettlementResponse settled(String payer, String transaction, String network,
			String amount) {
		return new SettlementResponse(true, null, payer, transaction, network, amount);
	}

	public static SettlementResponse failure(String reason, String payer, String network) {
		return new SettlementResponse(false, reason, payer, "", network, null);
	}

	public AMap<AString, ACell> toJSON() {
		AMap<AString, ACell> m = Maps.of(
				Fields.SUCCESS, CVMBool.create(success),
				Fields.TRANSACTION, Strings.create(transaction == null ? "" : transaction),
				Fields.NETWORK, Strings.create(network == null ? "" : network));
		if (errorReason != null) m = m.assoc(Fields.ERROR_REASON, Strings.create(errorReason));
		if (payer != null) m = m.assoc(Fields.PAYER, Strings.create(payer));
		if (amount != null) m = m.assoc(Fields.AMOUNT, Strings.create(amount));
		return m;
	}

	public static SettlementResponse fromJSON(AMap<AString, ACell> json) {
		if (json == null) throw new IllegalArgumentException("Missing SettlementResponse");
		ACell success = json.get(Fields.SUCCESS);
		if (success == null) throw new IllegalArgumentException("SettlementResponse requires success");
		return new SettlementResponse(RT.bool(success),
				Model.optString(json, Fields.ERROR_REASON),
				Model.optString(json, Fields.PAYER),
				Model.optString(json, Fields.TRANSACTION),
				Model.optString(json, Fields.NETWORK),
				Model.optString(json, Fields.AMOUNT));
	}
}
