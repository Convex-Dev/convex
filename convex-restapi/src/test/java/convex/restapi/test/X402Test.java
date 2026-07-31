package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Transfer;
import convex.core.data.Format;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.init.Init;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.peer.API;
import convex.peer.Server;
import convex.restapi.RESTConfig;
import convex.restapi.RESTServer;
import convex.x402.ErrorReasons;
import convex.x402.Fields;
import convex.x402.X402;
import convex.x402.client.X402Client;
import convex.x402.model.PaymentPayload;
import convex.x402.model.PaymentRequired;
import convex.x402.model.PaymentRequirements;
import convex.x402.model.SettlementResponse;
import convex.x402.model.VerifyResponse;

/**
 * End-to-end test of x402 payment support: gated route, facilitator endpoints,
 * payment flow, idempotent replay and stale payment rejection.
 */
public class X402Test {

	static final long PRICE = 1_000_000;
	static final Address PAY_TO = Address.create(13);
	static final String GATED_PATH = "/api/v1/status";

	static Server peerServer;
	static RESTServer restServer;
	static String hostPath;
	static AKeyPair kp;
	static Convex convex;

	static final HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10)).build();

	@BeforeAll
	static void setup() throws Exception {
		RESTConfig config = RESTConfig.parse("""
			{rest:{x402:{enabled:true, payTo:"#13",
				routes:[{path:"/api/v1/status", amount:"1000000", description:"Paid status"}]}}}
			""");
		var launchConfig = config.toLegacy();
		launchConfig.put(Keywords.KEYPAIR, AKeyPair.generate());
		launchConfig.put(Keywords.PORT, 0);
		peerServer = API.launchPeer(launchConfig);
		restServer = RESTServer.create(peerServer);
		restServer.start(0);
		hostPath = "http://localhost:" + restServer.getPort();
		kp = peerServer.getKeyPair();
		convex = Convex.connect(peerServer);
		convex.setAddress(Init.GENESIS_ADDRESS, kp);
	}

	@AfterAll
	static void tearDown() {
		if (restServer != null) restServer.close();
		if (peerServer != null) peerServer.close();
	}

	static URI gatedUri() {
		return URI.create(hostPath + GATED_PATH);
	}

	/** Fetches the payment demand for the gated route. */
	static PaymentRequired fetchDemand() throws Exception {
		HttpResponse<String> response = httpClient.send(
				HttpRequest.newBuilder(gatedUri()).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(402, response.statusCode());
		String header = response.headers().firstValue(X402.HEADER_PAYMENT_REQUIRED).orElseThrow();
		return PaymentRequired.fromJSON(X402.decodeHeader(header));
	}

	@Test
	public void testPaymentDemand() throws Exception {
		PaymentRequired demand = fetchDemand();
		assertEquals(X402.VERSION, demand.x402Version());
		assertEquals(1, demand.accepts().size());
		PaymentRequirements req = demand.accepts().get(0);
		assertEquals(X402.SCHEME_EXACT, req.scheme());
		assertEquals("1000000", req.amount());
		assertEquals("slip44:864", req.asset());
		assertEquals("#13", req.payTo());
		assertTrue(req.network().startsWith("convex:"));
		assertNotNull(demand.resource());
		assertTrue(demand.resource().url().endsWith(GATED_PATH));
	}

	@Test
	public void testMalformedPaymentRejected() throws Exception {
		// A structurally unparseable payment header maps to 400 per the x402 HTTP
		// transport; payment failures on well-formed payments stay 402
		HttpResponse<String> response = httpClient.send(
				HttpRequest.newBuilder(gatedUri()).GET()
						.header(X402.HEADER_PAYMENT_SIGNATURE, "!!not-base64!!").build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(400, response.statusCode());
	}

	@Test
	public void testSupported() throws Exception {
		HttpResponse<String> response = httpClient.send(
				HttpRequest.newBuilder(URI.create(hostPath + "/x402/supported")).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("\"exact\""));
		assertTrue(response.body().contains("convex:protonet"));
	}

	@Test
	public void testPaidFlowWithClient() throws Exception {
		long before = convex.getBalance(PAY_TO);

		X402Client client = X402Client.create(httpClient, convex, kp);
		HttpResponse<String> response = client.get(gatedUri());
		assertEquals(200, response.statusCode());

		SettlementResponse receipt = client.lastPaymentResponse();
		assertNotNull(receipt);
		assertTrue(receipt.success());
		assertTrue(receipt.transaction().startsWith("0x"));
		assertEquals(Init.GENESIS_ADDRESS.toString(), receipt.payer());

		assertEquals(before + PRICE, convex.getBalance(PAY_TO));
	}

	@Test
	public void testIdempotentReplayAndStaleRejection() throws Exception {
		PaymentRequirements req = fetchDemand().accepts().get(0);
		X402Client client = X402Client.create(httpClient, convex, kp);
		PaymentPayload payment = client.buildPayment(req);
		String paymentHeader = X402.encodeHeader(payment.toJSON());

		long before = convex.getBalance(PAY_TO);
		HttpResponse<String> paid = httpClient.send(
				HttpRequest.newBuilder(gatedUri()).GET()
						.header(X402.HEADER_PAYMENT_SIGNATURE, paymentHeader).build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, paid.statusCode());
		assertEquals(before + PRICE, convex.getBalance(PAY_TO));

		// Replaying the identical settled payment is idempotent: the resource is
		// served again but the payer is not charged again.
		HttpResponse<String> replay = httpClient.send(
				HttpRequest.newBuilder(gatedUri()).GET()
						.header(X402.HEADER_PAYMENT_SIGNATURE, paymentHeader).build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, replay.statusCode());
		assertEquals(before + PRICE, convex.getBalance(PAY_TO));

		// A payment with a wrong (future) sequence is rejected outright. A future
		// sequence is used because a stale one could coincide with the identical
		// already-settled transaction above, which settles idempotently by design.
		long badSequence = convex.lookupSequence(Init.GENESIS_ADDRESS) + 5;
		ATransaction wrongSeq = Transfer.create(Init.GENESIS_ADDRESS, badSequence, PAY_TO, PRICE);
		SignedData<ATransaction> staleSigned = kp.signData(wrongSeq);
		PaymentPayload stalePayment = new PaymentPayload(X402.VERSION, null, req,
				Maps.of(Fields.TRANSACTION,
						Strings.create(Format.encodeMultiCell(staleSigned, true).toHexString())));
		HttpResponse<String> rejected = httpClient.send(
				HttpRequest.newBuilder(gatedUri()).GET()
						.header(X402.HEADER_PAYMENT_SIGNATURE,
								X402.encodeHeader(stalePayment.toJSON()))
						.build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(402, rejected.statusCode());
		String header = rejected.headers().firstValue(X402.HEADER_PAYMENT_REQUIRED).orElseThrow();
		assertEquals(ErrorReasons.INVALID_SEQUENCE, PaymentRequired.fromJSON(X402.decodeHeader(header)).error());
		assertEquals(before + PRICE, convex.getBalance(PAY_TO));
	}

	@Test
	public void testFacilitatorVerifyAndSettle() throws Exception {
		PaymentRequirements req = fetchDemand().accepts().get(0);
		X402Client client = X402Client.create(httpClient, convex, kp);
		PaymentPayload payment = client.buildPayment(req);

		String body = JSON.toString(Maps.of(
				Fields.X402_VERSION, CVMLong.create(X402.VERSION),
				Fields.PAYMENT_PAYLOAD, payment.toJSON(),
				Fields.PAYMENT_REQUIREMENTS, req.toJSON()));

		// Verify: read-only, no state change
		HttpResponse<String> verifyResponse = httpClient.send(
				HttpRequest.newBuilder(URI.create(hostPath + "/x402/verify"))
						.header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, verifyResponse.statusCode());
		VerifyResponse verified = VerifyResponse
				.fromJSON(RT.castMap(JSON.parse(verifyResponse.body())));
		assertTrue(verified.isValid(), "verify failed: " + verifyResponse.body());
		assertEquals(Init.GENESIS_ADDRESS.toString(), verified.payer());

		// Settle: executes the payment
		long before = convex.getBalance(PAY_TO);
		HttpResponse<String> settleResponse = httpClient.send(
				HttpRequest.newBuilder(URI.create(hostPath + "/x402/settle"))
						.header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, settleResponse.statusCode());
		SettlementResponse settled = SettlementResponse
				.fromJSON(RT.castMap(JSON.parse(settleResponse.body())));
		assertTrue(settled.success(), "settle failed: " + settleResponse.body());
		assertEquals(before + PRICE, convex.getBalance(PAY_TO));

		// A wrong-network requirement is rejected
		PaymentRequirements wrongNetwork = new PaymentRequirements(req.scheme(), "eip155:8453",
				req.amount(), req.asset(), req.payTo(), req.maxTimeoutSeconds(), null);
		String badBody = JSON.toString(Maps.of(
				Fields.X402_VERSION, CVMLong.create(X402.VERSION),
				Fields.PAYMENT_PAYLOAD, payment.toJSON(),
				Fields.PAYMENT_REQUIREMENTS, wrongNetwork.toJSON()));
		HttpResponse<String> badResponse = httpClient.send(
				HttpRequest.newBuilder(URI.create(hostPath + "/x402/verify"))
						.header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString(badBody)).build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, badResponse.statusCode());
		VerifyResponse rejected = VerifyResponse
				.fromJSON(RT.castMap(JSON.parse(badResponse.body())));
		assertFalse(rejected.isValid());
		assertEquals(ErrorReasons.INVALID_NETWORK, rejected.invalidReason());

		// The peer facilitator's settle endpoint refuses foreign kinds identically
		HttpResponse<String> badSettle = httpClient.send(
				HttpRequest.newBuilder(URI.create(hostPath + "/x402/settle"))
						.header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString(badBody)).build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, badSettle.statusCode());
		SettlementResponse rejectedSettle = SettlementResponse
				.fromJSON(RT.castMap(JSON.parse(badSettle.body())));
		assertFalse(rejectedSettle.success());
		assertEquals(ErrorReasons.INVALID_NETWORK, rejectedSettle.errorReason());
	}

	@Test
	public void testCad29TokenPayment() throws Exception {
		// Fresh connection: its sequence tracking must not be confused by payments
		// settled out-of-band in other tests
		Convex deployer = Convex.connect(peerServer);
		deployer.setAddress(Init.GENESIS_ADDRESS, kp);
		convex.core.Result deployed = deployer
				.transactSync("(deploy (@convex.fungible/build-token {:supply 1000000}))");
		assertFalse(deployed.isError(), "token deploy failed: " + deployed);
		Address token = deployed.getValue();

		String network = fetchDemand().accepts().get(0).network();
		PaymentRequirements req = new PaymentRequirements(X402.SCHEME_EXACT, network, "500",
				"cad29:" + token.longValue(), "#13", 30, null);
		X402Client client = X402Client.create(httpClient, deployer, kp);
		PaymentPayload payment = client.buildPayment(req);

		String body = JSON.toString(Maps.of(
				Fields.X402_VERSION, CVMLong.create(X402.VERSION),
				Fields.PAYMENT_PAYLOAD, payment.toJSON(),
				Fields.PAYMENT_REQUIREMENTS, req.toJSON()));
		HttpResponse<String> settleResponse = httpClient.send(
				HttpRequest.newBuilder(URI.create(hostPath + "/x402/settle"))
						.header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, settleResponse.statusCode());
		SettlementResponse settled = SettlementResponse
				.fromJSON(RT.castMap(JSON.parse(settleResponse.body())));
		assertTrue(settled.success(), "token settle failed: " + settleResponse.body());

		convex.core.Result balance = deployer
				.querySync("(@convex.fungible/balance " + token + " #13)");
		assertEquals(CVMLong.create(500), balance.getValue());
	}
}
