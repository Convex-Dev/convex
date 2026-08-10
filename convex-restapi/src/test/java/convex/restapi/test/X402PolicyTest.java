package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.transactions.ATransaction;
import convex.core.data.Format;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.init.Init;
import convex.peer.API;
import convex.peer.Server;
import convex.restapi.handler.X402Decision;
import convex.restapi.handler.X402Gate;
import convex.restapi.handler.X402Policy;
import convex.x402.Fields;
import convex.x402.NetworkId;
import convex.x402.X402;
import convex.x402.client.X402Client;
import convex.x402.facilitator.ConvexFacilitator;
import convex.x402.model.PaymentPayload;
import convex.x402.model.PaymentRequirements;
import convex.x402.model.SettlementResponse;
import convex.x402.scheme.ExactConvexScheme;
import io.javalin.Javalin;
import io.javalin.http.Context;

/**
 * Tests the embedder story: a standalone Javalin app (no RESTServer) protected
 * by an X402Gate with a custom credit policy — one payment buys several calls,
 * requests are free while credit lasts, and exhausting credit produces a fresh
 * payment demand. Also exercises the onSettled replay veto.
 */
public class X402PolicyTest {

	static final long CALL_PRICE = 1_000_000;
	static final int CALLS_PER_TOP_UP = 3;

	static Server peerServer;
	static Javalin app;
	static String paidUrl;
	static AKeyPair kp;
	static Convex convex;
	static CreditPolicy policy;

	static final HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10)).build();

	/**
	 * Credit semantics: serve from credit while it lasts; when exhausted, demand
	 * one payment covering {@link #CALLS_PER_TOP_UP} calls. Credits are keyed
	 * globally for test simplicity; a real service keys them by caller identity.
	 */
	static class CreditPolicy implements X402Policy {
		final PaymentRequirements topUp;
		int credits = 0;
		final Set<String> settledTransactions = new HashSet<>();

		CreditPolicy(X402Gate gate) {
			topUp = gate.price(Long.toString(CALL_PRICE * CALLS_PER_TOP_UP),
					X402Client.cvmAsset(), "#13");
		}

		@Override
		public synchronized X402Decision decide(Context ctx) {
			if (credits > 0) {
				credits--;
				return X402Decision.allow();
			}
			return X402Decision.charge(topUp, "Top up " + CALLS_PER_TOP_UP + " calls");
		}

		@Override
		public synchronized boolean onSettled(Context ctx, PaymentPayload payment,
				SettlementResponse receipt) {
			if (!settledTransactions.add(receipt.transaction())) {
				return false; // replayed payment: value already consumed, do not serve
			}
			credits += CALLS_PER_TOP_UP - 1; // the settled request itself is one call
			return true;
		}
	}

	@BeforeAll
	static void setup() throws Exception {
		var launchConfig = new java.util.HashMap<convex.core.data.Keyword, Object>();
		launchConfig.put(Keywords.KEYPAIR, AKeyPair.generate());
		launchConfig.put(Keywords.PORT, 0);
		peerServer = API.launchPeer(launchConfig);
		kp = peerServer.getKeyPair();
		convex = Convex.connect(peerServer);
		convex.setAddress(Init.GENESIS_ADDRESS, kp);

		ConvexFacilitator facilitator = new ConvexFacilitator(convex,
				NetworkId.create(peerServer.getPeer().getNetworkID()),
				hash -> peerServer.getPeer().getTransaction(hash) != null);

		app = Javalin.create(config -> {
			X402Gate gate = new X402Gate(facilitator);
			policy = new CreditPolicy(gate);
			gate.protect(config.routes, "/paid/*", policy);
			config.routes.get("/paid/hello", ctx -> ctx.result("served:" + X402Gate.getPayer(ctx)));
		});
		app.start(0);
		paidUrl = "http://localhost:" + app.port() + "/paid/hello";
	}

	@AfterAll
	static void tearDown() {
		if (app != null) app.stop();
		if (peerServer != null) peerServer.close();
	}

	static HttpResponse<String> plainGet() throws Exception {
		return httpClient.send(HttpRequest.newBuilder(URI.create(paidUrl)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
	}

	@Test
	public void testCreditSemantics() throws Exception {
		// No credit: demand payment
		HttpResponse<String> unpaid = plainGet();
		assertEquals(402, unpaid.statusCode());
		assertTrue(unpaid.headers().firstValue(X402.HEADER_PAYMENT_REQUIRED).isPresent());

		// Pay the top-up: served, and the payer address reaches the handler
		X402Client client = X402Client.create(httpClient, convex, kp);
		HttpResponse<String> paid = client.get(URI.create(paidUrl));
		assertEquals(200, paid.statusCode());
		assertEquals("served:" + Init.GENESIS_ADDRESS, paid.body());
		SettlementResponse receipt = client.lastPaymentResponse();
		assertNotNull(receipt);
		assertTrue(receipt.success());

		// Two further calls are served free from credit
		HttpResponse<String> free1 = plainGet();
		assertEquals(200, free1.statusCode());
		assertEquals("served:null", free1.body()); // no payment on this request
		assertEquals(200, plainGet().statusCode());

		// Credit exhausted: fresh payment demand
		assertEquals(402, plainGet().statusCode());

		// Replaying the earlier settled payment is refused: its value was consumed.
		// Ed25519 signing is deterministic, so rebuilding the canonical transaction
		// with the already-used sequence reproduces the settled payment exactly.
		PaymentRequirements req = client.lastPaymentRequired().accepts().get(0);
		ATransaction settledTx = ExactConvexScheme.expectedTransaction(Init.GENESIS_ADDRESS,
				convex.lookupSequence(Init.GENESIS_ADDRESS), Address.parse(req.payTo()), req);
		SignedData<ATransaction> settledSigned = kp.signData(settledTx);
		PaymentPayload replay = new PaymentPayload(X402.VERSION, null, req,
				Maps.of(Fields.TRANSACTION,
						Strings.create(Format.encodeMultiCell(settledSigned, true).toHexString())));
		HttpResponse<String> replayed = httpClient.send(
				HttpRequest.newBuilder(URI.create(paidUrl)).GET()
						.header(X402.HEADER_PAYMENT_SIGNATURE,
								X402.encodeHeader(replay.toJSON()))
						.build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(402, replayed.statusCode());

		// A fresh payment tops up again
		HttpResponse<String> paidAgain = client.get(URI.create(paidUrl));
		assertEquals(200, paidAgain.statusCode());
	}
}
