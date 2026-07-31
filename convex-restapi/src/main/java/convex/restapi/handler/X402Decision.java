package convex.restapi.handler;

import java.util.List;

import convex.x402.model.PaymentRequirements;

/**
 * An {@link X402Policy} decision for one request at a payment-gated route.
 *
 * @param kind What to do with the request
 * @param requirements Acceptable payment options when kind is CHARGE, else null
 * @param description Human-readable description of what is being bought, may be null
 * @param status HTTP status when kind is DENY
 * @param message Response message when kind is DENY, may be null
 */
public record X402Decision(Kind kind, List<PaymentRequirements> requirements, String description,
		int status, String message) {

	public enum Kind {
		/** Serve the request without payment (e.g. credit was consumed) */
		ALLOW,
		/** Require an x402 payment satisfying one of the given requirements */
		CHARGE,
		/** Refuse the request regardless of payment */
		DENY
	}

	private static final X402Decision ALLOW_INSTANCE =
			new X402Decision(Kind.ALLOW, null, null, 200, null);

	/** Serves the request without payment. */
	public static X402Decision allow() {
		return ALLOW_INSTANCE;
	}

	/** Requires a payment satisfying the given requirements. */
	public static X402Decision charge(PaymentRequirements requirements) {
		return charge(requirements, null);
	}

	/** Requires a payment satisfying the given requirements, with a description. */
	public static X402Decision charge(PaymentRequirements requirements, String description) {
		if (requirements == null) throw new IllegalArgumentException("Charge requires payment requirements");
		return charge(List.of(requirements), description);
	}

	/**
	 * Requires a payment satisfying any one of the given options — e.g. CVM
	 * coin on Convex or USDC on Base — with a description. The gate routes the
	 * client's chosen option to a facilitator that handles it.
	 */
	public static X402Decision charge(List<PaymentRequirements> options, String description) {
		if ((options == null) || options.isEmpty()) {
			throw new IllegalArgumentException("Charge requires at least one payment option");
		}
		return new X402Decision(Kind.CHARGE, List.copyOf(options), description, 402, null);
	}

	/** Refuses the request with the given HTTP status, regardless of payment. */
	public static X402Decision deny(int status, String message) {
		return new X402Decision(Kind.DENY, null, null, status, message);
	}
}
