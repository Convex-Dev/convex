package convex.x402.scheme;

import convex.api.Convex;
import convex.core.Coin;
import convex.core.Result;
import convex.core.cvm.Address;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Call;
import convex.core.cvm.transactions.Transfer;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Blobs;
import convex.core.data.SignedData;
import convex.core.data.Symbol;
import convex.core.data.Vectors;
import convex.core.data.prim.AInteger;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.PartialMessageException;
import convex.core.exceptions.ResultException;
import convex.core.lang.RT;
import convex.core.message.Message;
import convex.core.util.CAIP;
import convex.x402.ErrorReasons;
import convex.x402.Fields;
import convex.x402.PaymentError;
import convex.x402.model.PaymentRequirements;

/**
 * The x402 "exact" scheme binding for Convex (see CAD042).
 *
 * <p>A payment is a pre-signed Convex transaction in one of two canonical
 * forms:</p>
 * <ul>
 * <li>Native CVM coin ({@code slip44:864}): a {@code Transfer} of exactly the
 * required amount to the {@code payTo} address.</li>
 * <li>CAD29 fungible token ({@code cad29:<addr>}): a {@code Call} equivalent to
 * {@code (call <token> (direct-transfer <payTo> <amount> nil))}.</li>
 * </ul>
 *
 * <p>Structural verification reconstructs the canonical transaction from the
 * requirements and the presented origin/sequence, then requires cell equality.
 * This whitelists every field at once: nothing but origin and sequence is left
 * free, and those are then checked against consensus state.</p>
 */
public class ExactConvexScheme {

	/** Symbol for the CAD29 fungible direct transfer callable */
	public static final Symbol DIRECT_TRANSFER = Symbol.create("direct-transfer");

	/**
	 * Maximum encoded size in bytes of a payment transaction. Canonical payment
	 * transactions are under 300 bytes; this bound keeps adversarial decoding cheap.
	 */
	public static final long MAX_TRANSACTION_BYTES = 4096;

	/**
	 * Coin headroom in copper required beyond the payment amount, covering juice
	 * fees for the transfer. Deliberately generous: fees are of the order of a few
	 * thousand copper, and verification precision here buys nothing.
	 */
	public static final long FEE_ALLOWANCE = 1_000_000;

	/**
	 * Decodes the signed transaction from an exact scheme payment payload.
	 *
	 * <p>Decoding is storeless and size-bounded: payloads are attacker-controlled,
	 * so missing branches fail rather than touching the peer store.</p>
	 *
	 * @param schemePayload The scheme-specific payload object containing a
	 *        "transaction" field of hex CAD3 data
	 * @return Decoded signed transaction
	 * @throws IllegalArgumentException if the payload is missing, oversized,
	 *         malformed or not a signed transaction
	 */
	@SuppressWarnings("unchecked")
	public static SignedData<ATransaction> decodeTransaction(AMap<AString, ACell> schemePayload) {
		if (schemePayload == null) throw new IllegalArgumentException("Missing payment payload");
		AString hex = RT.ensureString(schemePayload.get(Fields.TRANSACTION));
		if (hex == null) throw new IllegalArgumentException("Payment payload requires a 'transaction' hex string");
		if (hex.count() > MAX_TRANSACTION_BYTES * 2) {
			throw new IllegalArgumentException("Payment transaction exceeds maximum size");
		}
		ABlob data = Blobs.parse(hex.toString());
		if (data == null) throw new IllegalArgumentException("Payment transaction is not valid hex");

		final ACell value;
		try {
			value = Message.create(data.toFlatBlob()).getPayload(null);
		} catch (BadFormatException | PartialMessageException e) {
			throw new IllegalArgumentException("Payment transaction is not a valid encoding: " + e.getMessage(), e);
		}
		if ((value instanceof SignedData<?> sd) && (sd.getValue() instanceof ATransaction)) {
			return (SignedData<ATransaction>) value;
		}
		throw new IllegalArgumentException("Payment data is not a signed transaction");
	}

	/**
	 * Checks that a signed transaction has the canonical form for the given
	 * payment requirements. Stateless: signature, sequence and funds are checked
	 * separately by {@link #checkState(Convex, SignedData, PaymentRequirements)}.
	 *
	 * @param sd Signed transaction to check
	 * @param req Payment requirements to check against
	 * @return null if canonical, otherwise the payment error
	 */
	public static PaymentError checkStructure(SignedData<ATransaction> sd, PaymentRequirements req) {
		ATransaction tx = sd.getValue();
		Address origin = tx.getOrigin();
		if (origin == null) {
			return PaymentError.of(ErrorReasons.INVALID_TRANSACTION,
					"transaction has no origin address");
		}
		long sequence = tx.getSequence();

		Address payTo = Address.parse(req.payTo());
		if (payTo == null) {
			return PaymentError.of(ErrorReasons.INVALID_PAYMENT_REQUIREMENTS,
					"payTo is not a valid Convex address: " + req.payTo());
		}

		ATransaction expected = expectedTransaction(origin, sequence, payTo, req);
		if (expected == null) {
			return PaymentError.of(ErrorReasons.INVALID_PAYMENT_REQUIREMENTS,
					"amount '" + req.amount() + "' or asset '" + req.asset()
							+ "' is not usable with the exact Convex scheme"
							+ " (scoped cad29 assets are not yet supported)");
		}
		if (!expected.equals(tx)) {
			return PaymentError.of(ErrorReasons.INVALID_TRANSACTION,
					"transaction does not match the canonical form; expected " + expected
							+ " but payment contains " + tx);
		}
		return null;
	}

	/**
	 * Constructs the canonical payment transaction for the given requirements.
	 *
	 * @param origin Payer account
	 * @param sequence Transaction sequence number
	 * @param payTo Recipient account
	 * @param req Payment requirements
	 * @return Canonical transaction, or null if the requirements are unusable
	 */
	public static ATransaction expectedTransaction(Address origin, long sequence, Address payTo,
			PaymentRequirements req) {
		String asset = req.asset();
		if (asset == null) return null;
		if (CAIP.isCVM(asset)) {
			final long amount;
			try {
				amount = Long.parseLong(req.amount());
			} catch (NumberFormatException e) {
				return null;
			}
			if (!Coin.isValidAmount(amount)) return null;
			return Transfer.create(origin, sequence, payTo, amount);
		}

		final ACell tokenID;
		try {
			tokenID = CAIP.parseTokenID(asset);
		} catch (IllegalArgumentException e) {
			return null;
		}
		// Scoped CAD29 assets are not yet supported (see CAD042)
		if (!(tokenID instanceof Address token)) return null;
		final AInteger amount;
		try {
			amount = AInteger.parse(req.amount());
		} catch (RuntimeException e) {
			return null;
		}
		if (amount == null) return null;
		return Call.create(origin, sequence, token, Call.DEFAULT_OFFER, DIRECT_TRANSFER,
				Vectors.of(payTo, amount, null));
	}

	/**
	 * Checks a structurally-valid payment transaction against current consensus
	 * state: signature against the origin's account key, next sequence number, and
	 * sufficient funds including the {@link #FEE_ALLOWANCE}.
	 *
	 * @param convex Client connection used for state reads
	 * @param sd Signed transaction to check
	 * @param req Payment requirements the transaction satisfies structurally
	 * @return null if the payment is expected to settle, otherwise the payment error
	 * @throws InterruptedException if interrupted while querying
	 */
	public static PaymentError checkState(Convex convex, SignedData<ATransaction> sd,
			PaymentRequirements req) throws InterruptedException {
		ATransaction tx = sd.getValue();
		Address origin = tx.getOrigin();

		AccountKey key = convex.getAccountKey(origin);
		if (key == null) {
			return PaymentError.of(ErrorReasons.INVALID_SIGNATURE, "origin account " + origin
					+ " does not exist or has no account key (actor accounts cannot pay)");
		}
		if (!sd.checkSignature(key)) {
			return PaymentError.of(ErrorReasons.INVALID_SIGNATURE,
					"signature does not verify against the current account key for " + origin
							+ "; was the transaction signed with this account's current key?");
		}

		final long current;
		try {
			current = convex.getSequence(origin);
		} catch (ResultException e) {
			return PaymentError.of(ErrorReasons.INVALID_TRANSACTION,
					"could not read the sequence for " + origin + ": " + e.getMessage());
		}
		if (tx.getSequence() != current + 1) {
			return PaymentError.of(ErrorReasons.INVALID_SEQUENCE,
					"transaction sequence is " + tx.getSequence() + " but the next valid sequence for "
							+ origin + " is " + (current + 1)
							+ "; rebuild the payment with the current sequence");
		}

		final long coinBalance;
		try {
			coinBalance = convex.getBalance(origin);
		} catch (ResultException e) {
			return PaymentError.of(ErrorReasons.INVALID_TRANSACTION,
					"could not read the balance for " + origin + ": " + e.getMessage());
		}

		if (CAIP.isCVM(req.asset())) {
			long amount = Long.parseLong(req.amount()); // structural check ensured validity
			if (coinBalance < amount + FEE_ALLOWANCE) {
				return PaymentError.of(ErrorReasons.INSUFFICIENT_FUNDS,
						"balance of " + origin + " is " + coinBalance + " copper, below the amount "
								+ amount + " plus fee allowance " + FEE_ALLOWANCE);
			}
		} else {
			if (coinBalance < FEE_ALLOWANCE) {
				return PaymentError.of(ErrorReasons.INSUFFICIENT_FUNDS,
						"coin balance of " + origin + " is " + coinBalance
								+ " copper, below the fee allowance " + FEE_ALLOWANCE
								+ " needed to pay juice for the token transfer");
			}
			ACell tokenID = CAIP.parseTokenID(req.asset());
			AInteger amount = AInteger.parse(req.amount());
			// All components are canonically printed values, so the code is injection-safe
			String code = "(< (@convex.fungible/balance " + tokenID + " " + origin + ") " + amount + ")";
			Result r = convex.querySync(code);
			if (r.isError()) {
				return PaymentError.of(ErrorReasons.INVALID_PAYMENT_REQUIREMENTS,
						"token balance query failed for asset '" + req.asset() + "': "
								+ r.getValue());
			}
			if (RT.bool(r.getValue())) {
				return PaymentError.of(ErrorReasons.INSUFFICIENT_FUNDS, "token balance of " + origin
						+ " is below the amount " + req.amount() + " for asset '" + req.asset() + "'");
			}
		}
		return null;
	}
}
