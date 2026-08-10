package convex.x402;

/**
 * A payment failure: a machine-readable x402 reason code plus a human-readable
 * diagnostic detail.
 *
 * <p>The reason code is what client software (and x402 tooling) switches on;
 * the detail is what lets a caller — human or agent — fix the payment without
 * guesswork: which sequence to use, which network forms are accepted, what the
 * canonical transaction should have been.</p>
 *
 * @param reason x402 error reason code, from {@link ErrorReasons}
 * @param detail Human-readable diagnostic, may be null
 */
public record PaymentError(String reason, String detail) {

	public static PaymentError of(String reason, String detail) {
		return new PaymentError(reason, detail);
	}

	/** The reason and detail combined, e.g. for a human-readable error field. */
	public String message() {
		return (detail == null) ? reason : reason + ": " + detail;
	}
}
