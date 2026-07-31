package convex.x402;

import convex.core.data.AString;
import convex.core.data.Strings;

/**
 * JSON field names used by the x402 v2 protocol. Names follow the upstream
 * specification exactly.
 */
public class Fields {
	public static final AString X402_VERSION = Strings.intern("x402Version");
	public static final AString ERROR = Strings.intern("error");
	public static final AString RESOURCE = Strings.intern("resource");
	public static final AString ACCEPTS = Strings.intern("accepts");

	// ResourceInfo
	public static final AString URL = Strings.intern("url");
	public static final AString DESCRIPTION = Strings.intern("description");
	public static final AString MIME_TYPE = Strings.intern("mimeType");

	// PaymentRequirements
	public static final AString SCHEME = Strings.intern("scheme");
	public static final AString NETWORK = Strings.intern("network");
	public static final AString AMOUNT = Strings.intern("amount");
	public static final AString ASSET = Strings.intern("asset");
	public static final AString PAY_TO = Strings.intern("payTo");
	public static final AString MAX_TIMEOUT_SECONDS = Strings.intern("maxTimeoutSeconds");
	public static final AString EXTRA = Strings.intern("extra");

	// PaymentPayload
	public static final AString ACCEPTED = Strings.intern("accepted");
	public static final AString PAYLOAD = Strings.intern("payload");

	// Convex exact scheme payload
	public static final AString TRANSACTION = Strings.intern("transaction");

	// VerifyResponse
	public static final AString IS_VALID = Strings.intern("isValid");
	public static final AString INVALID_REASON = Strings.intern("invalidReason");
	public static final AString PAYER = Strings.intern("payer");

	// SettlementResponse
	public static final AString SUCCESS = Strings.intern("success");
	public static final AString ERROR_REASON = Strings.intern("errorReason");

	// Facilitator endpoint bodies
	public static final AString PAYMENT_PAYLOAD = Strings.intern("paymentPayload");
	public static final AString PAYMENT_REQUIREMENTS = Strings.intern("paymentRequirements");
	public static final AString KINDS = Strings.intern("kinds");
	public static final AString EXTENSIONS = Strings.intern("extensions");
	public static final AString SIGNERS = Strings.intern("signers");
}
