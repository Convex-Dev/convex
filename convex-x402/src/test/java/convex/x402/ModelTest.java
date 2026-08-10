package convex.x402;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.x402.model.PaymentPayload;
import convex.x402.model.PaymentRequired;
import convex.x402.model.PaymentRequirements;
import convex.x402.model.ResourceInfo;
import convex.x402.model.SettlementResponse;
import convex.x402.model.VerifyResponse;

public class ModelTest {

	private static final PaymentRequirements REQ = new PaymentRequirements("exact",
			"convex:testnet", "1000000", "slip44:864", "#13", 30, null);

	@Test
	public void testPaymentRequirementsRoundTrip() {
		assertEquals(REQ, PaymentRequirements.fromJSON(REQ.toJSON()));
	}

	@Test
	public void testPaymentRequirementsMissingFields() {
		assertThrows(IllegalArgumentException.class, () -> PaymentRequirements.fromJSON(null));
		assertThrows(IllegalArgumentException.class,
				() -> PaymentRequirements.fromJSON(Maps.of(Fields.SCHEME, Strings.create("exact"))));
	}

	@Test
	public void testPaymentRequiredHeaderRoundTrip() {
		PaymentRequired required = PaymentRequired.create(
				new ResourceInfo("https://example.com/data", "Test resource", null), REQ);
		String header = X402.encodeHeader(required.toJSON());
		PaymentRequired decoded = PaymentRequired.fromJSON(X402.decodeHeader(header));
		assertEquals(required, decoded);
		assertEquals(X402.VERSION, decoded.x402Version());
		assertNull(decoded.error());
	}

	@Test
	public void testPaymentPayloadRoundTrip() {
		PaymentPayload payload = new PaymentPayload(X402.VERSION, null, REQ,
				Maps.of(Fields.TRANSACTION, Strings.create("abcd1234")));
		assertEquals(payload, PaymentPayload.fromJSON(X402.decodeHeader(X402.encodeHeader(payload.toJSON()))));
	}

	@Test
	public void testVerifyResponseRoundTrip() {
		VerifyResponse valid = VerifyResponse.valid("#42");
		assertEquals(valid, VerifyResponse.fromJSON(valid.toJSON()));
		VerifyResponse invalid = VerifyResponse.invalid(ErrorReasons.INSUFFICIENT_FUNDS, "#42");
		assertEquals(invalid, VerifyResponse.fromJSON(invalid.toJSON()));
	}

	@Test
	public void testSettlementResponseRoundTrip() {
		SettlementResponse settled = SettlementResponse.settled("#42", "0x1234", "convex:testnet",
				"1000000");
		assertEquals(settled, SettlementResponse.fromJSON(settled.toJSON()));
		SettlementResponse failed = SettlementResponse.failure(ErrorReasons.INVALID_SEQUENCE, "#42",
				"convex:testnet");
		SettlementResponse decoded = SettlementResponse.fromJSON(failed.toJSON());
		assertEquals(failed, decoded);
		assertTrue(!decoded.success());
	}

	@Test
	public void testDecodeHeaderRejectsBadInput() {
		assertThrows(IllegalArgumentException.class, () -> X402.decodeHeader(null));
		assertThrows(IllegalArgumentException.class, () -> X402.decodeHeader("!!not-base64!!"));
		// Valid base64 but not JSON
		String notJson = Base64.getEncoder().encodeToString("hello world <".getBytes());
		assertThrows(IllegalArgumentException.class, () -> X402.decodeHeader(notJson));
		// Valid base64 JSON but not an object
		String notObject = Base64.getEncoder().encodeToString("[1,2,3]".getBytes());
		assertThrows(IllegalArgumentException.class, () -> X402.decodeHeader(notObject));
		// Oversized
		String big = "A".repeat((int) (X402.MAX_HEADER_BYTES * 4 / 3) + 100);
		assertThrows(IllegalArgumentException.class, () -> X402.decodeHeader(big));
	}
}
