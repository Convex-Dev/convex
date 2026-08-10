package convex.restapi.handler;

import convex.x402.model.PaymentPayload;
import convex.x402.model.SettlementResponse;
import io.javalin.http.Context;

/**
 * Payment policy for an x402-gated route, supplied by the embedding service.
 *
 * <p>A policy decides per request whether to serve freely, demand a payment,
 * or refuse — enabling models beyond fixed price per call, such as credit
 * accounts (consume credit while it lasts, demand a top-up payment when it
 * runs out) or per-identity pricing. {@link X402Gate#fixedPrice} provides the
 * simple fixed-price-per-call policy.</p>
 *
 * <h2>Contract</h2>
 *
 * <ul>
 * <li>{@link #decide} runs before any payment processing, on every request to
 * the gated path. It MUST be deterministic for a given request between the
 * initial 402 response and the client's paid retry: the retry is settled
 * against the requirements returned by the <em>second</em> call, so a changed
 * price rejects the payment and the client sees a fresh 402.</li>
 * <li>{@link #onSettled} runs after a payment for this request has settled
 * successfully, before the protected handler. Use it to credit accounts,
 * meter usage or record receipts.</li>
 * <li><b>Replays</b>: settlement is idempotent — a client retrying with an
 * already-settled payment gets settlement success without being charged
 * again, and {@code onSettled} runs again for the same transaction.
 * Implementations keeping balances MUST deduplicate by
 * {@link SettlementResponse#transaction()}, and SHOULD return false for a
 * replay whose value has already been consumed; the gate then responds 402
 * instead of serving.</li>
 * <li><b>Failures</b>: an exception from {@code onSettled} propagates (the
 * request fails) but the payment stays settled — money has moved. A client
 * retry settles idempotently and re-runs the hook, so deduplicating
 * implementations converge.</li>
 * </ul>
 *
 * <p>For the ALLOW path the caller's identity must come from the request
 * (e.g. bearer-token authentication); for settled payments the payer address
 * is available via {@link X402Gate#getPayer(Context)}.</p>
 */
public interface X402Policy {

	/**
	 * Decides how to handle a request to the gated route, before any payment
	 * processing.
	 *
	 * @param ctx Request context
	 * @return The decision: allow, charge or deny
	 * @throws InterruptedException if interrupted (e.g. during a state read)
	 */
	X402Decision decide(Context ctx) throws InterruptedException;

	/**
	 * Called when a payment for this request has settled successfully, before
	 * the protected handler runs. See the class contract for replay and
	 * failure semantics.
	 *
	 * @param ctx Request context
	 * @param payment The payment the client supplied
	 * @param receipt The settlement receipt, including payer and transaction hash
	 * @return true to serve the request; false to refuse it with a 402 (e.g. a
	 *         replayed payment whose value was already consumed)
	 */
	default boolean onSettled(Context ctx, PaymentPayload payment, SettlementResponse receipt) {
		return true;
	}
}
