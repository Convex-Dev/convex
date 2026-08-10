package convex.x402.facilitator;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.x402.model.PaymentPayload;
import convex.x402.model.PaymentRequirements;
import convex.x402.model.SettlementResponse;
import convex.x402.model.VerifyResponse;

/**
 * The x402 facilitator role: verifies and settles payments for the payment
 * kinds (scheme, network pairs) it handles.
 *
 * <p>Implementations: {@link ConvexFacilitator} settles the Convex exact
 * scheme directly against a Convex network; {@link RemoteFacilitator} routes
 * to any spec-compliant external facilitator over HTTP, enabling foreign
 * schemes and networks such as USDC on Base. A resource server may combine
 * several facilitators to accept several payment kinds.</p>
 *
 * <p>Implementations MUST NOT throw for ordinary payment failures — they
 * report them in the returned response — so callers can treat any exception
 * as an infrastructure fault.</p>
 */
public interface Facilitator {

	/**
	 * Verifies a payment without settling it.
	 *
	 * @param payload Payment payload from the client
	 * @param requirements Payment requirements being satisfied
	 * @return Verification outcome (never null; failures are reported inside)
	 * @throws InterruptedException if interrupted
	 */
	VerifyResponse verify(PaymentPayload payload, PaymentRequirements requirements)
			throws InterruptedException;

	/**
	 * Settles a payment. Only a successful settlement is proof of payment.
	 *
	 * @param payload Payment payload from the client
	 * @param requirements Payment requirements being satisfied
	 * @return Settlement outcome (never null; failures are reported inside)
	 * @throws InterruptedException if interrupted
	 */
	SettlementResponse settle(PaymentPayload payload, PaymentRequirements requirements)
			throws InterruptedException;

	/**
	 * Tests whether this facilitator handles a payment kind. Used for routing
	 * when several facilitators are combined. Implementations SHOULD fail
	 * closed: return false when support cannot be determined.
	 *
	 * @param scheme Payment scheme identifier, e.g. "exact"
	 * @param network CAIP-2 network identifier, e.g. "convex:protonet" or "eip155:8453"
	 * @return true if this facilitator can verify and settle the kind
	 */
	boolean handles(String scheme, String network);

	/**
	 * Builds the x402 /supported response listing the payment kinds this
	 * facilitator handles.
	 *
	 * @return JSON structure for the /supported endpoint
	 * @throws InterruptedException if interrupted
	 */
	AMap<AString, ACell> supported() throws InterruptedException;
}
