package convex.x402.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Optional;

import convex.api.Convex;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.transactions.ATransaction;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Format;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.exceptions.ResultException;
import convex.core.util.CAIP;
import convex.x402.Fields;
import convex.x402.NetworkId;
import convex.x402.X402;
import convex.x402.model.PaymentPayload;
import convex.x402.model.PaymentRequired;
import convex.x402.model.PaymentRequirements;
import convex.x402.model.SettlementResponse;
import convex.x402.scheme.ExactConvexScheme;

/**
 * HTTP client wrapper that pays x402 payment demands with Convex transactions.
 *
 * <p>On a 402 response carrying a {@code PAYMENT-REQUIRED} header, the client
 * selects a Convex exact scheme requirement it can satisfy, constructs and
 * signs the canonical payment transaction, and retries the request with a
 * {@code PAYMENT-SIGNATURE} header. Keys never leave the client.</p>
 *
 * <p>Payments consume account sequence numbers, so a client making concurrent
 * payments must serialise payment construction; this class synchronises
 * payment construction per instance.</p>
 */
public class X402Client {

	protected final HttpClient http;
	protected final Convex convex;
	protected final AKeyPair keyPair;
	protected final Address address;

	private volatile PaymentRequired lastPaymentRequired;
	private volatile SettlementResponse lastPaymentResponse;

	protected X402Client(HttpClient http, Convex convex, AKeyPair keyPair, Address address) {
		this.http = http;
		this.convex = convex;
		this.keyPair = keyPair;
		this.address = address;
	}

	/**
	 * Creates a paying client.
	 * @param http HTTP client to send requests with
	 * @param convex Convex connection used to look up the payer's next sequence
	 * @param keyPair Payer's key pair, used only for local signing
	 * @return New client instance paying from the connection's current address
	 */
	public static X402Client create(HttpClient http, Convex convex, AKeyPair keyPair) {
		Address address = convex.getAddress();
		if (address == null) throw new IllegalArgumentException("Convex connection requires an address");
		return new X402Client(http, convex, keyPair, address);
	}

	/**
	 * Sends a request, paying an x402 payment demand if one comes back.
	 *
	 * @param request Request to send
	 * @return Final response: the original response if no payment was demanded
	 *         or none could be constructed, otherwise the paid retry's response
	 * @throws IOException on connection failure
	 * @throws InterruptedException if interrupted
	 * @throws ResultException if the payer's account state cannot be read
	 */
	public HttpResponse<String> send(HttpRequest request)
			throws IOException, InterruptedException, ResultException {
		HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
		if (response.statusCode() != 402) return response;

		Optional<String> header = response.headers().firstValue(X402.HEADER_PAYMENT_REQUIRED);
		if (header.isEmpty()) return response;

		final PaymentRequired required;
		try {
			required = PaymentRequired.fromJSON(X402.decodeHeader(header.get()));
		} catch (IllegalArgumentException e) {
			return response;
		}
		lastPaymentRequired = required;

		PaymentRequirements requirements = selectRequirement(required);
		if (requirements == null) return response; // nothing this client can pay

		PaymentPayload payment = buildPayment(requirements);
		HttpRequest paid = HttpRequest.newBuilder(request, (name, value) -> true)
				.setHeader(X402.HEADER_PAYMENT_SIGNATURE, X402.encodeHeader(payment.toJSON()))
				.build();
		HttpResponse<String> paidResponse = http.send(paid, BodyHandlers.ofString());
		paidResponse.headers().firstValue(X402.HEADER_PAYMENT_RESPONSE).ifPresent(v -> {
			try {
				lastPaymentResponse = SettlementResponse.fromJSON(X402.decodeHeader(v));
			} catch (IllegalArgumentException e) {
				// A malformed receipt does not invalidate the response itself
			}
		});
		return paidResponse;
	}

	/**
	 * Convenience GET with payment handling.
	 * @param uri URI to fetch
	 * @return Final response, as {@link #send(HttpRequest)}
	 */
	public HttpResponse<String> get(URI uri)
			throws IOException, InterruptedException, ResultException {
		return send(HttpRequest.newBuilder(uri).GET().build());
	}

	/**
	 * Selects the first payment requirement this client can satisfy: the exact
	 * scheme on a Convex network, in the native coin or an unscoped CAD29 token.
	 *
	 * @param required Payment demand from the server
	 * @return Chosen requirements, or null if none are payable
	 */
	protected PaymentRequirements selectRequirement(PaymentRequired required) {
		for (PaymentRequirements req : required.accepts()) {
			if (!X402.SCHEME_EXACT.equals(req.scheme())) continue;
			String network = req.network();
			if ((network == null) || !network.startsWith(NetworkId.NAMESPACE + ":")) continue;
			if (Address.parse(req.payTo()) == null) continue;
			if (ExactConvexScheme.expectedTransaction(address, 1, Address.parse(req.payTo()), req) == null) {
				continue; // unusable asset or amount
			}
			return req;
		}
		return null;
	}

	/**
	 * Builds and signs the canonical payment for the given requirements, using
	 * the payer's next sequence number.
	 *
	 * @param requirements Payment requirements to satisfy
	 * @return Signed payment payload ready for the PAYMENT-SIGNATURE header
	 * @throws InterruptedException if interrupted
	 * @throws ResultException if the payer's sequence cannot be read
	 */
	public synchronized PaymentPayload buildPayment(PaymentRequirements requirements)
			throws InterruptedException, ResultException {
		// Always read the sequence from consensus: the connection's cached sequence
		// goes stale when payments settle outside its own transact flow, and a
		// reused sequence would replay the previous payment instead of paying.
		long sequence = convex.lookupSequence(address) + 1;
		Address payTo = Address.parse(requirements.payTo());
		if (payTo == null) throw new IllegalArgumentException("Invalid payTo address: " + requirements.payTo());
		ATransaction tx = ExactConvexScheme.expectedTransaction(address, sequence, payTo, requirements);
		if (tx == null) throw new IllegalArgumentException("Unpayable requirements: " + requirements);
		SignedData<ATransaction> sd = keyPair.signData(tx);
		AMap<AString, ACell> payload = Maps.of(Fields.TRANSACTION,
				Strings.create(Format.encodeMultiCell(sd, true).toHexString()));
		return new PaymentPayload(X402.VERSION, null, requirements, payload);
	}

	/** Gets the CVM native coin asset identifier, for building requirements. */
	public static String cvmAsset() {
		return CAIP.CONVEX_ASSET_ID.toString();
	}

	/**
	 * Gets the last payment demand received, or null if none.
	 */
	public PaymentRequired lastPaymentRequired() {
		return lastPaymentRequired;
	}

	/**
	 * Gets the last settlement receipt received, or null if none.
	 */
	public SettlementResponse lastPaymentResponse() {
		return lastPaymentResponse;
	}
}
