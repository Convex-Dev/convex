package convex.x402;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Call;
import convex.core.cvm.transactions.Invoke;
import convex.core.cvm.transactions.Transfer;
import convex.core.data.Format;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.AInteger;
import convex.x402.model.PaymentRequirements;
import convex.x402.scheme.ExactConvexScheme;

/**
 * Structural verification tests for the Convex exact scheme. State-dependent
 * checks (signature, sequence, funds) are covered by the integration test in
 * convex-restapi.
 */
public class ExactConvexSchemeTest {

	private static final AKeyPair KP = AKeyPair.createSeeded(123456);
	private static final Address ORIGIN = Address.create(101);
	private static final Address PAY_TO = Address.create(13);
	private static final Address TOKEN = Address.create(789);

	private static final PaymentRequirements CVM_REQ = new PaymentRequirements("exact",
			"convex:local", "1000000", "slip44:864", "#13", 30, null);
	private static final PaymentRequirements TOKEN_REQ = new PaymentRequirements("exact",
			"convex:local", "500", "cad29:789", "#13", 30, null);

	private static SignedData<ATransaction> sign(ATransaction tx) {
		return KP.signData(tx);
	}

	@Test
	public void testCanonicalCoinTransfer() {
		SignedData<ATransaction> sd = sign(Transfer.create(ORIGIN, 5, PAY_TO, 1000000));
		assertNull(ExactConvexScheme.checkStructure(sd, CVM_REQ));
	}

	@Test
	public void testCanonicalTokenTransfer() {
		Call call = Call.create(ORIGIN, 7, TOKEN, Call.DEFAULT_OFFER,
				ExactConvexScheme.DIRECT_TRANSFER, Vectors.of(PAY_TO, AInteger.create(500), null));
		assertNull(ExactConvexScheme.checkStructure(sign(call), TOKEN_REQ));
	}

	@Test
	public void testNonCanonicalTransactionsRejected() {
		// Wrong amount
		assertEquals(ErrorReasons.INVALID_TRANSACTION, ExactConvexScheme
				.checkStructure(sign(Transfer.create(ORIGIN, 5, PAY_TO, 999999)), CVM_REQ));
		// Wrong recipient
		assertEquals(ErrorReasons.INVALID_TRANSACTION, ExactConvexScheme
				.checkStructure(sign(Transfer.create(ORIGIN, 5, Address.create(14), 1000000)), CVM_REQ));
		// Wrong transaction type: an Invoke computing the same transfer is not canonical
		assertEquals(ErrorReasons.INVALID_TRANSACTION, ExactConvexScheme
				.checkStructure(sign(Invoke.create(ORIGIN, 5, "(transfer #13 1000000)")), CVM_REQ));
		// Coin transfer presented against a token requirement
		assertEquals(ErrorReasons.INVALID_TRANSACTION, ExactConvexScheme
				.checkStructure(sign(Transfer.create(ORIGIN, 5, PAY_TO, 500)), TOKEN_REQ));
	}

	@Test
	public void testNonCanonicalCallsRejected() {
		// Non-zero offer
		assertEquals(ErrorReasons.INVALID_TRANSACTION,
				ExactConvexScheme.checkStructure(sign(Call.create(ORIGIN, 7, TOKEN, 1,
						ExactConvexScheme.DIRECT_TRANSFER,
						Vectors.of(PAY_TO, AInteger.create(500), null))), TOKEN_REQ));
		// Wrong function name
		assertEquals(ErrorReasons.INVALID_TRANSACTION,
				ExactConvexScheme.checkStructure(sign(Call.create(ORIGIN, 7, TOKEN,
						Call.DEFAULT_OFFER, convex.core.data.Symbol.create("transfer"),
						Vectors.of(PAY_TO, AInteger.create(500), null))), TOKEN_REQ));
		// Wrong token actor
		assertEquals(ErrorReasons.INVALID_TRANSACTION,
				ExactConvexScheme.checkStructure(sign(Call.create(ORIGIN, 7, Address.create(790),
						Call.DEFAULT_OFFER, ExactConvexScheme.DIRECT_TRANSFER,
						Vectors.of(PAY_TO, AInteger.create(500), null))), TOKEN_REQ));
		// Missing nil data argument
		assertEquals(ErrorReasons.INVALID_TRANSACTION,
				ExactConvexScheme.checkStructure(sign(Call.create(ORIGIN, 7, TOKEN,
						Call.DEFAULT_OFFER, ExactConvexScheme.DIRECT_TRANSFER,
						Vectors.of(PAY_TO, AInteger.create(500)))), TOKEN_REQ));
	}

	@Test
	public void testUnusableRequirementsRejected() {
		SignedData<ATransaction> sd = sign(Transfer.create(ORIGIN, 5, PAY_TO, 1000000));
		// Scoped CAD29 assets are not yet supported
		assertEquals(ErrorReasons.INVALID_PAYMENT_REQUIREMENTS, ExactConvexScheme.checkStructure(sd,
				new PaymentRequirements("exact", "convex:local", "500", "cad29:789-56", "#13", 30, null)));
		// Unknown asset namespace
		assertEquals(ErrorReasons.INVALID_PAYMENT_REQUIREMENTS, ExactConvexScheme.checkStructure(sd,
				new PaymentRequirements("exact", "convex:local", "500", "erc20:0x1234", "#13", 30, null)));
		// Malformed amount
		assertEquals(ErrorReasons.INVALID_PAYMENT_REQUIREMENTS, ExactConvexScheme.checkStructure(sd,
				new PaymentRequirements("exact", "convex:local", "lots", "slip44:864", "#13", 30, null)));
		// Negative amount
		assertEquals(ErrorReasons.INVALID_PAYMENT_REQUIREMENTS, ExactConvexScheme.checkStructure(sd,
				new PaymentRequirements("exact", "convex:local", "-5", "slip44:864", "#13", 30, null)));
		// Malformed payTo
		assertEquals(ErrorReasons.INVALID_PAYMENT_REQUIREMENTS, ExactConvexScheme.checkStructure(sd,
				new PaymentRequirements("exact", "convex:local", "1000000", "slip44:864", "nowhere", 30, null)));
	}

	@Test
	public void testDecodeTransactionRoundTrip() {
		SignedData<ATransaction> sd = sign(Transfer.create(ORIGIN, 5, PAY_TO, 1000000));
		String hex = Format.encodeMultiCell(sd, true).toHexString();
		SignedData<ATransaction> decoded = ExactConvexScheme
				.decodeTransaction(Maps.of(Fields.TRANSACTION, Strings.create(hex)));
		assertEquals(sd, decoded);
	}

	@Test
	public void testDecodeTransactionRejectsBadInput() {
		// Missing payload / field
		assertThrows(IllegalArgumentException.class, () -> ExactConvexScheme.decodeTransaction(null));
		assertThrows(IllegalArgumentException.class,
				() -> ExactConvexScheme.decodeTransaction(Maps.empty()));
		// Not hex
		assertThrows(IllegalArgumentException.class, () -> ExactConvexScheme
				.decodeTransaction(Maps.of(Fields.TRANSACTION, Strings.create("zznothex"))));
		// Valid encoding but not a signed transaction
		String plain = Format.encodeMultiCell(Transfer.create(ORIGIN, 5, PAY_TO, 1000000), true)
				.toHexString();
		assertThrows(IllegalArgumentException.class, () -> ExactConvexScheme
				.decodeTransaction(Maps.of(Fields.TRANSACTION, Strings.create(plain))));
		// Oversized
		String big = "00".repeat((int) ExactConvexScheme.MAX_TRANSACTION_BYTES + 1);
		assertThrows(IllegalArgumentException.class, () -> ExactConvexScheme
				.decodeTransaction(Maps.of(Fields.TRANSACTION, Strings.create(big))));
	}
}
