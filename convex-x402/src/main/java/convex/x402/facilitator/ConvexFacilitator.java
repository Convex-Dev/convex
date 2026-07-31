package convex.x402.facilitator;

import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.cvm.transactions.ATransaction;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.x402.ErrorReasons;
import convex.x402.Fields;
import convex.x402.NetworkId;
import convex.x402.PaymentError;
import convex.x402.X402;
import convex.x402.model.PaymentPayload;
import convex.x402.model.PaymentRequirements;
import convex.x402.model.SettlementResponse;
import convex.x402.model.VerifyResponse;
import convex.x402.scheme.ExactConvexScheme;

/**
 * x402 facilitator core for Convex: verifies and settles exact scheme payments
 * against a Convex network via a client connection.
 *
 * <p>The facilitator never signs anything and holds no funds: it verifies
 * client-signed transactions against consensus state and relays them unchanged
 * for settlement. A peer's REST server can therefore act as its own
 * facilitator with no third-party trust.</p>
 */
public class ConvexFacilitator implements Facilitator {
	private static final Logger log = LoggerFactory.getLogger(ConvexFacilitator.class);

	/** Hard ceiling on the settlement wait, regardless of requirements */
	public static final long MAX_SETTLE_TIMEOUT_MILLIS = 30_000;

	protected final Convex convex;
	protected final NetworkId networkId;
	protected final Predicate<Hash> confirmedCheck;

	/**
	 * Creates a facilitator.
	 * @param convex Client connection used for state reads and settlement
	 * @param networkId Identity of the network settled against
	 */
	public ConvexFacilitator(Convex convex, NetworkId networkId) {
		this(convex, networkId, null);
	}

	/**
	 * Creates a facilitator with an idempotency check.
	 * @param convex Client connection used for state reads and settlement
	 * @param networkId Identity of the network settled against
	 * @param confirmedCheck Optional test for whether a transaction hash is
	 *        already confirmed in consensus, enabling idempotent settlement of
	 *        retried requests. May be null.
	 */
	public ConvexFacilitator(Convex convex, NetworkId networkId, Predicate<Hash> confirmedCheck) {
		if (convex == null) throw new IllegalArgumentException("Convex connection required");
		if (networkId == null) throw new IllegalArgumentException("Network ID required");
		this.convex = convex;
		this.networkId = networkId;
		this.confirmedCheck = confirmedCheck;
	}

	public NetworkId getNetworkId() {
		return networkId;
	}

	@Override
	public boolean handles(String scheme, String network) {
		return X402.SCHEME_EXACT.equals(scheme) && networkId.matches(network);
	}

	/**
	 * Verifies a payment without settling it. Read-only and free.
	 *
	 * @param payload Payment payload from the client
	 * @param requirements Payment requirements being satisfied
	 * @return Verification outcome
	 * @throws InterruptedException if interrupted while querying state
	 */
	public VerifyResponse verify(PaymentPayload payload, PaymentRequirements requirements)
			throws InterruptedException {
		Checked c = check(payload, requirements);
		if (c.error != null) return VerifyResponse.invalid(c.error, c.payer);
		return VerifyResponse.valid(c.payer);
	}

	/**
	 * Settles a payment: verifies it, then submits the signed transaction and
	 * awaits the consensus result.
	 *
	 * @param payload Payment payload from the client
	 * @param requirements Payment requirements being satisfied
	 * @return Settlement outcome
	 * @throws InterruptedException if interrupted while settling
	 */
	public SettlementResponse settle(PaymentPayload payload, PaymentRequirements requirements)
			throws InterruptedException {
		Checked c = check(payload, requirements);
		if (c.error != null) return SettlementResponse.failure(c.error, c.payer, networkId.canonical());

		SignedData<ATransaction> sd = c.transaction;
		String txHash = "0x" + sd.getHash().toHexString();
		if (c.alreadySettled) {
			return SettlementResponse.settled(c.payer, txHash, networkId.canonical(),
					requirements.amount());
		}
		long timeoutMillis = requirements.maxTimeoutSeconds() * 1000;
		if ((timeoutMillis <= 0) || (timeoutMillis > MAX_SETTLE_TIMEOUT_MILLIS)) {
			timeoutMillis = MAX_SETTLE_TIMEOUT_MILLIS;
		}

		Result r = convex.transactSync(sd, timeoutMillis);
		if (!r.isError()) {
			return SettlementResponse.settled(c.payer, txHash, networkId.canonical(),
					requirements.amount());
		}
		// A retried request can race its own earlier settlement between check and
		// submission; an already-confirmed transaction still settles idempotently.
		if (ErrorCodes.SEQUENCE.equals(r.getErrorCode()) && isConfirmed(sd)) {
			return SettlementResponse.settled(c.payer, txHash, networkId.canonical(),
					requirements.amount());
		}
		return SettlementResponse.failure(settlementReason(r), c.payer, networkId.canonical());
	}

	/** Maps a failed settlement Result to a payment error with diagnostic detail. */
	private PaymentError settlementReason(Result r) {
		ACell error = r.getErrorCode();
		String value = truncate(String.valueOf(r.getValue()));
		if (ErrorCodes.SEQUENCE.equals(error)) {
			return PaymentError.of(ErrorReasons.INVALID_SEQUENCE,
					"transaction rejected with :SEQUENCE (already spent or out of order): " + value
							+ "; rebuild the payment with the current sequence");
		}
		if (ErrorCodes.FUNDS.equals(error)) {
			return PaymentError.of(ErrorReasons.INSUFFICIENT_FUNDS,
					"transaction rejected with :FUNDS: " + value);
		}
		if (ErrorCodes.SIGNATURE.equals(error)) {
			return PaymentError.of(ErrorReasons.INVALID_SIGNATURE,
					"transaction rejected with :SIGNATURE: " + value);
		}
		if (ErrorCodes.TIMEOUT.equals(error)) {
			return PaymentError.of(ErrorReasons.INVALID_TRANSACTION_STATE,
					"settlement not confirmed within the timeout; it may still confirm —"
							+ " retrying with the same payment settles idempotently");
		}
		log.warn("Unexpected x402 settlement failure {}: {}", error, r.getValue());
		return PaymentError.of(ErrorReasons.UNEXPECTED_SETTLE_ERROR,
				"transaction rejected with " + error + ": " + value);
	}

	private static String truncate(String s) {
		return (s.length() <= 300) ? s : s.substring(0, 300) + "…";
	}

	/** Outcome of shared verification: a decoded transaction or a payment error. */
	protected record Checked(SignedData<ATransaction> transaction, String payer,
			PaymentError error, boolean alreadySettled) {
		static Checked fail(PaymentError error, String payer) {
			return new Checked(null, payer, error, false);
		}

		static Checked ok(SignedData<ATransaction> transaction, String payer) {
			return new Checked(transaction, payer, null, false);
		}
	}

	/**
	 * Shared verification for verify and settle.
	 */
	protected Checked check(PaymentPayload payload, PaymentRequirements requirements)
			throws InterruptedException {
		if ((payload == null) || (requirements == null)) {
			return Checked.fail(PaymentError.of(ErrorReasons.INVALID_PAYLOAD,
					"missing payment payload or payment requirements"), null);
		}
		if (payload.x402Version() != X402.VERSION) {
			return Checked.fail(PaymentError.of(ErrorReasons.INVALID_X402_VERSION,
					"expected x402Version " + X402.VERSION + " but payment has "
							+ payload.x402Version()), null);
		}
		if (!X402.SCHEME_EXACT.equals(requirements.scheme())) {
			return Checked.fail(PaymentError.of(ErrorReasons.UNSUPPORTED_SCHEME,
					"scheme '" + requirements.scheme() + "' is not supported; this facilitator"
							+ " supports '" + X402.SCHEME_EXACT + "'"), null);
		}
		if (!networkId.matches(requirements.network())) {
			return Checked.fail(PaymentError.of(ErrorReasons.INVALID_NETWORK,
					"network '" + requirements.network() + "' does not match this facilitator;"
							+ " recognised forms: " + networkId.knownForms()), null);
		}

		final SignedData<ATransaction> sd;
		try {
			sd = ExactConvexScheme.decodeTransaction(payload.payload());
		} catch (IllegalArgumentException e) {
			return Checked.fail(PaymentError.of(ErrorReasons.INVALID_PAYLOAD, e.getMessage()), null);
		}
		String payer = sd.getValue().getOrigin().toString();

		PaymentError error = ExactConvexScheme.checkStructure(sd, requirements);
		if (error != null) return Checked.fail(error, payer);

		error = ExactConvexScheme.checkState(convex, sd, requirements);
		if (error != null) {
			// An already-settled retry shows up as a stale sequence; report it as
			// success so settlement is idempotent for the resource server.
			if (ErrorReasons.INVALID_SEQUENCE.equals(error.reason()) && isConfirmed(sd)) {
				return new Checked(sd, payer, null, true);
			}
			return Checked.fail(error, payer);
		}
		return Checked.ok(sd, payer);
	}

	private boolean isConfirmed(SignedData<ATransaction> sd) {
		return (confirmedCheck != null) && confirmedCheck.test(sd.getHash());
	}

	/**
	 * Builds the x402 /supported response listing payment kinds this facilitator
	 * handles. The signers map is empty by design: the Convex exact scheme never
	 * requires facilitator signatures.
	 *
	 * @return JSON structure for the /supported endpoint
	 */
	public AMap<AString, ACell> supported() {
		AVector<ACell> kinds = Vectors.empty();
		for (String network : networkId.knownForms()) {
			kinds = kinds.conj(Maps.of(
					Fields.X402_VERSION, CVMLong.create(X402.VERSION),
					Fields.SCHEME, Strings.create(X402.SCHEME_EXACT),
					Fields.NETWORK, Strings.create(network)));
		}
		return Maps.of(
				Fields.KINDS, kinds,
				Fields.EXTENSIONS, Vectors.empty(),
				Fields.SIGNERS, Maps.empty());
	}
}
