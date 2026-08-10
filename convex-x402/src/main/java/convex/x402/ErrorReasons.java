package convex.x402;

/**
 * Standard x402 error reason codes, plus the Convex exact scheme reasons.
 */
public class ErrorReasons {
	public static final String INSUFFICIENT_FUNDS = "insufficient_funds";
	public static final String INVALID_SCHEME = "invalid_scheme";
	public static final String UNSUPPORTED_SCHEME = "unsupported_scheme";
	public static final String INVALID_NETWORK = "invalid_network";
	public static final String INVALID_X402_VERSION = "invalid_x402_version";
	public static final String INVALID_PAYLOAD = "invalid_payload";
	public static final String INVALID_PAYMENT_REQUIREMENTS = "invalid_payment_requirements";
	public static final String INVALID_TRANSACTION_STATE = "invalid_transaction_state";
	public static final String UNEXPECTED_VERIFY_ERROR = "unexpected_verify_error";
	public static final String UNEXPECTED_SETTLE_ERROR = "unexpected_settle_error";

	/** Transaction does not match the canonical form for the payment requirements */
	public static final String INVALID_TRANSACTION = "invalid_exact_convex_payload_transaction";
	/** Signature does not verify against the origin account's current account key */
	public static final String INVALID_SIGNATURE = "invalid_exact_convex_payload_signature";
	/** Sequence number is not the origin account's next sequence */
	public static final String INVALID_SEQUENCE = "invalid_exact_convex_payload_sequence";
}
