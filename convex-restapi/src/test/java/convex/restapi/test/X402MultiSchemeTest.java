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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.init.Init;
import convex.peer.API;
import convex.peer.Server;
import convex.restapi.handler.X402Decision;
import convex.restapi.handler.X402Gate;
import convex.x402.ErrorReasons;
import convex.x402.NetworkId;
import convex.x402.X402;
import convex.x402.client.X402Client;
import convex.x402.facilitator.ConvexFacilitator;
import convex.x402.facilitator.RemoteFacilitator;
import convex.x402.model.PaymentPayload;
import convex.x402.model.PaymentRequired;
import convex.x402.model.PaymentRequirements;
import convex.x402.model.SettlementResponse;
import convex.x402.model.VerifyResponse;
import io.javalin.Javalin;

/**
 * Tests accepting foreign payment kinds via a remote facilitator: a gate
 * offering both CVM-on-Convex and USDC-on-Base options, with the Base option
 * settled through a stub external facilitator speaking the standard x402
 * facilitator API.
 */
public class X402MultiSchemeTest {

	static final String BASE_NETWORK = "eip155:8453";
	static final String USDC_ASSET = "erc20:0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913";
	static final String EVM_TX = "0x9e3284d1";

	static Server peerServer;
	static Javalin stubFacilitator;
	static Javalin app;
	static String paidUrl;
	static AKeyPair kp;
	static Convex convex;
	static ConvexFacilitator convexFacilitator;
	static RemoteFacilitator remote;
	static final AtomicInteger stubSettleCalls = new AtomicInteger();

	static final HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10)).build();

	@BeforeAll
	static void setup() throws Exception {
		var launchConfig = new java.util.HashMap<convex.core.data.Keyword, Object>();
		launchConfig.put(Keywords.KEYPAIR, AKeyPair.generate());
		launchConfig.put(Keywords.PORT, 0);
		peerServer = API.launchPeer(launchConfig);
		kp = peerServer.getKeyPair();
		convex = Convex.connect(peerServer);
		convex.setAddress(Init.GENESIS_ADDRESS, kp);

		// Stub external facilitator speaking the standard /verify /settle /supported API
		stubFacilitator = Javalin.create(config -> {
			config.routes.get("/facilitator/supported", ctx -> ctx.contentType("application/json").result(
					"{\"kinds\":[{\"x402Version\":2,\"scheme\":\"exact\",\"network\":\"" + BASE_NETWORK
							+ "\"}],\"extensions\":[],\"signers\":{}}"));
			config.routes.post("/facilitator/verify", ctx -> ctx.contentType("application/json")
					.result("{\"isValid\":true,\"payer\":\"0xPayer\"}"));
			config.routes.post("/facilitator/settle", ctx -> {
				stubSettleCalls.incrementAndGet();
				ctx.contentType("application/json").result("{\"success\":true,\"payer\":\"0xPayer\","
						+ "\"transaction\":\"" + EVM_TX + "\",\"network\":\"" + BASE_NETWORK + "\"}");
			});
		});
		stubFacilitator.start(0);

		convexFacilitator = new ConvexFacilitator(convex,
				NetworkId.create(peerServer.getPeer().getNetworkID()),
				hash -> peerServer.getPeer().getTransaction(hash) != null);
		remote = RemoteFacilitator.create(httpClient,
				URI.create("http://localhost:" + stubFacilitator.port() + "/facilitator"));

		app = Javalin.create(config -> {
			X402Gate gate = new X402Gate(convexFacilitator, remote);
			PaymentRequirements convexOption = gate.price("1000000", X402Client.cvmAsset(), "#13");
			PaymentRequirements usdcOption = new PaymentRequirements(X402.SCHEME_EXACT,
					BASE_NETWORK, "10000", USDC_ASSET, "0xServiceWallet", 30, null);
			gate.protect(config.routes, "/paid/*", ctx -> X402Decision
					.charge(List.of(convexOption, usdcOption), "Pay in CVM or USDC"));
			config.routes.get("/paid/data", ctx -> ctx.result("data"));
		});
		app.start(0);
		paidUrl = "http://localhost:" + app.port() + "/paid/data";
	}

	@AfterAll
	static void tearDown() {
		if (app != null) app.stop();
		if (stubFacilitator != null) stubFacilitator.stop();
		if (peerServer != null) peerServer.close();
	}

	static PaymentRequired fetchDemand() throws Exception {
		HttpResponse<String> response = httpClient.send(
				HttpRequest.newBuilder(URI.create(paidUrl)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(402, response.statusCode());
		return PaymentRequired.fromJSON(
				X402.decodeHeader(response.headers().firstValue(X402.HEADER_PAYMENT_REQUIRED).orElseThrow()));
	}

	@Test
	public void testBothOptionsOffered() throws Exception {
		PaymentRequired demand = fetchDemand();
		assertEquals(2, demand.accepts().size());
		assertTrue(demand.accepts().stream().anyMatch(r -> r.network().startsWith("convex:")));
		assertTrue(demand.accepts().stream().anyMatch(r -> BASE_NETWORK.equals(r.network())));
	}

	@Test
	public void testForeignPaymentViaRemoteFacilitator() throws Exception {
		// Choose the USDC-on-Base option exactly as offered; the payload content is
		// opaque to the gate — the remote facilitator owns its verification
		PaymentRequirements usdc = fetchDemand().accepts().stream()
				.filter(r -> BASE_NETWORK.equals(r.network())).findFirst().orElseThrow();
		PaymentPayload payment = new PaymentPayload(X402.VERSION, null, usdc,
				Maps.of(Strings.create("signature"),
						Strings.create("0xEvmSignatureBlob")));

		int before = stubSettleCalls.get();
		HttpResponse<String> paid = httpClient.send(
				HttpRequest.newBuilder(URI.create(paidUrl)).GET()
						.header(X402.HEADER_PAYMENT_SIGNATURE, X402.encodeHeader(payment.toJSON()))
						.build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, paid.statusCode());
		assertEquals("data", paid.body());
		assertEquals(before + 1, stubSettleCalls.get());

		SettlementResponse receipt = SettlementResponse.fromJSON(X402.decodeHeader(
				paid.headers().firstValue(X402.HEADER_PAYMENT_RESPONSE).orElseThrow()));
		assertTrue(receipt.success());
		assertEquals(EVM_TX, receipt.transaction());
		assertEquals(BASE_NETWORK, receipt.network());
		assertEquals("0xPayer", receipt.payer());
	}

	@Test
	public void testConvexPaymentStillWorks() throws Exception {
		long before = convex.getBalance(Address.create(13));
		X402Client client = X402Client.create(httpClient, convex, kp);
		HttpResponse<String> paid = client.get(URI.create(paidUrl));
		assertEquals(200, paid.statusCode());
		assertTrue(client.lastPaymentResponse().success());
		assertEquals(before + 1_000_000, convex.getBalance(Address.create(13)));
	}

	@Test
	public void testRemoteFacilitatorDirectly() throws Exception {
		// Kind discovery from /supported, failing closed for unknown kinds
		assertTrue(remote.handles(X402.SCHEME_EXACT, BASE_NETWORK));
		assertFalse(remote.handles(X402.SCHEME_EXACT, "eip155:1"));
		assertFalse(remote.handles("upto", BASE_NETWORK));

		PaymentRequirements usdc = new PaymentRequirements(X402.SCHEME_EXACT, BASE_NETWORK,
				"10000", USDC_ASSET, "0xServiceWallet", 30, null);
		PaymentPayload payment = new PaymentPayload(X402.VERSION, null, usdc,
				Maps.of(Strings.create("signature"),
						Strings.create("0xEvmSignatureBlob")));
		VerifyResponse verified = remote.verify(payment, usdc);
		assertTrue(verified.isValid());
		assertEquals("0xPayer", verified.payer());
		assertNotNull(remote.supported());
	}

	@Test
	public void testConvexFacilitatorRejectsForeignKind() throws Exception {
		// Routing correctness rests on the Convex facilitator disclaiming foreign
		// kinds, and on it failing cleanly if handed one anyway
		assertFalse(convexFacilitator.handles(X402.SCHEME_EXACT, BASE_NETWORK));
		assertTrue(convexFacilitator.handles(X402.SCHEME_EXACT, "convex:protonet"));
		assertTrue(convexFacilitator.handles(X402.SCHEME_EXACT,
				convexFacilitator.getNetworkId().canonical()));

		PaymentRequirements usdc = new PaymentRequirements(X402.SCHEME_EXACT, BASE_NETWORK,
				"10000", USDC_ASSET, "0xServiceWallet", 30, null);
		PaymentPayload payment = new PaymentPayload(X402.VERSION, null, usdc,
				Maps.of(Strings.create("signature"), Strings.create("0xEvmSignatureBlob")));

		VerifyResponse verified = convexFacilitator.verify(payment, usdc);
		assertFalse(verified.isValid());
		assertEquals(ErrorReasons.INVALID_NETWORK, verified.invalidReason());

		SettlementResponse settled = convexFacilitator.settle(payment, usdc);
		assertFalse(settled.success());
		assertEquals(ErrorReasons.INVALID_NETWORK, settled.errorReason());
	}

	@Test
	public void testMismatchedAcceptedRejected() throws Exception {
		// A payment whose accepted echo matches none of the offered options is
		// rejected before any facilitator is consulted
		PaymentRequirements tampered = new PaymentRequirements(X402.SCHEME_EXACT, BASE_NETWORK,
				"1", USDC_ASSET, "0xServiceWallet", 30, null); // wrong amount
		PaymentPayload payment = new PaymentPayload(X402.VERSION, null, tampered,
				Maps.of(Strings.create("signature"),
						Strings.create("0xEvmSignatureBlob")));
		int before = stubSettleCalls.get();
		HttpResponse<String> response = httpClient.send(
				HttpRequest.newBuilder(URI.create(paidUrl)).GET()
						.header(X402.HEADER_PAYMENT_SIGNATURE, X402.encodeHeader(payment.toJSON()))
						.build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(402, response.statusCode());
		assertEquals(before, stubSettleCalls.get());
	}
}
