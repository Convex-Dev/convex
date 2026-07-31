package convex.x402;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.exceptions.ParseException;
import convex.core.lang.RT;
import convex.core.util.JSON;

/**
 * Constants and header codecs for the x402 payment protocol (v2).
 *
 * <p>Over HTTP, all x402 protocol information travels in headers as
 * base64-encoded JSON. See CAD042 and the upstream x402 v2 specification.</p>
 */
public class X402 {
	/** Protocol version implemented ({@code x402Version}) */
	public static final int VERSION = 2;

	/** Response header carrying a base64 JSON PaymentRequired object */
	public static final String HEADER_PAYMENT_REQUIRED = "PAYMENT-REQUIRED";

	/** Request header carrying a base64 JSON PaymentPayload object */
	public static final String HEADER_PAYMENT_SIGNATURE = "PAYMENT-SIGNATURE";

	/** Response header carrying a base64 JSON SettlementResponse object */
	public static final String HEADER_PAYMENT_RESPONSE = "PAYMENT-RESPONSE";

	/** The exact payment scheme identifier */
	public static final String SCHEME_EXACT = "exact";

	/**
	 * Maximum decoded size in bytes accepted for an x402 header. Headers are
	 * attacker-controlled on public endpoints, so decoding is strictly bounded.
	 */
	public static final long MAX_HEADER_BYTES = 16384;

	/**
	 * Encodes a JSON value as a base64 x402 header value.
	 * @param json JSON structure to encode
	 * @return Base64 encoding of the JSON string
	 */
	public static String encodeHeader(ACell json) {
		String s = JSON.toString(json);
		return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Decodes a base64 x402 header value to a JSON object map.
	 * @param headerValue Base64 header value
	 * @return Decoded JSON object
	 * @throws IllegalArgumentException if the value is oversized, not valid base64,
	 *         not valid JSON, or not a JSON object
	 */
	public static AMap<AString, ACell> decodeHeader(String headerValue) {
		if (headerValue == null) throw new IllegalArgumentException("Null x402 header");
		// 4/3 base64 expansion plus padding slack
		if (headerValue.length() > (MAX_HEADER_BYTES * 4) / 3 + 4) {
			throw new IllegalArgumentException("x402 header exceeds maximum size");
		}
		byte[] bytes = Base64.getDecoder().decode(headerValue.trim());
		ACell value;
		try {
			value = JSON.parse(new String(bytes, StandardCharsets.UTF_8));
		} catch (ParseException e) {
			throw new IllegalArgumentException("x402 header is not valid JSON: " + e.getMessage(), e);
		}
		AMap<AString, ACell> map = RT.castMap(value);
		if (map == null) throw new IllegalArgumentException("x402 header is not a JSON object");
		return map;
	}
}
