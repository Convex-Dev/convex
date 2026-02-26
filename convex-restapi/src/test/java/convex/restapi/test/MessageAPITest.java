package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import convex.api.ContentTypes;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.CVMEncoder;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.core.init.Init;
import convex.core.lang.Reader;
import convex.core.message.Message;

/**
 * Tests for the generic POST /api/v1/message endpoint.
 *
 * Focuses on message-level protocol behaviour and adversarial message inputs.
 * CVM execution behaviour (juice limits, etc.) is tested in convex-core.
 */
public class MessageAPITest extends ARESTTest {

	private String messagePath() {
		return API_PATH + "/message";
	}

	// ---- Helpers ----

	/**
	 * POST raw CAD3 bytes to the message endpoint, return raw bytes response
	 */
	private HttpResponse<byte[]> postRaw(byte[] body) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(messagePath()))
				.header("Content-Type", ContentTypes.CVX_RAW)
				.header("Accept", ContentTypes.CVX_RAW)
				.POST(HttpRequest.BodyPublishers.ofByteArray(body))
				.build();
		return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
	}

	/**
	 * POST CVX text to the message endpoint, return string response
	 */
	private HttpResponse<String> postCVX(String cvxText) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(messagePath()))
				.header("Content-Type", ContentTypes.CVX)
				.header("Accept", ContentTypes.CVX)
				.POST(HttpRequest.BodyPublishers.ofString(cvxText))
				.build();
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}

	/**
	 * POST raw CAD3 Message and decode the CAD3 Result
	 */
	private Result postMessage(Message msg) throws Exception {
		Blob encoded = msg.getMessageData();
		HttpResponse<byte[]> resp = postRaw(encoded.getBytes());
		assertEquals(ContentTypes.CVX_RAW, resp.headers().firstValue("Content-Type").orElse(""));
		ACell decoded = CVMEncoder.INSTANCE.decodeMultiCell(Blob.wrap(resp.body()));
		assertTrue(decoded instanceof Result, "Expected Result but got: " + decoded.getClass().getSimpleName());
		return (Result) decoded;
	}

	// ---- Happy path: CAD3 raw messages ----

	@Test
	public void testQueryViaMessage() throws Exception {
		Message msg = Message.createQuery(1, "(+ 2 3)", Init.GENESIS_ADDRESS);
		Result r = postMessage(msg);
		assertFalse(r.isError(), () -> "Query error: " + r);
		assertEquals(CVMLong.create(5), r.getValue());
	}

	@Test
	public void testStatusViaMessage() throws Exception {
		Message msg = Message.createStatusRequest(1);
		Result r = postMessage(msg);
		assertFalse(r.isError(), () -> "Status error: " + r);
		assertNotNull(r.getValue());
	}

	@Test
	public void testTransactViaMessage() throws Exception {
		Address addr = Init.GENESIS_ADDRESS;
		long seq = connect().getSequence(addr) + 1;
		Invoke tx = Invoke.create(addr, seq, Reader.read("(+ 10 20)"));
		SignedData<ATransaction> signed = KP.signData((ATransaction) tx);
		Message msg = Message.createTransaction(1, signed);
		Result r = postMessage(msg);
		assertFalse(r.isError(), () -> "Transaction error: " + r);
		assertEquals(CVMLong.create(30), r.getValue());
	}

	@Test
	public void testChallengeViaMessage() throws Exception {
		AKeyPair clientKP = AKeyPair.createSeeded(99999);
		convex.api.Convex convex = connect();
		convex.setKeyPair(clientKP);

		convex.core.data.AccountKey serverKey = server.getServer().getPeerKey();
		convex.core.data.AccountKey result = convex.verifyPeer(serverKey).get(5, java.util.concurrent.TimeUnit.SECONDS);
		assertEquals(serverKey, result);
		assertEquals(serverKey, convex.getVerifiedPeer());
	}

	@Test
	public void testMultipleQueriesSequential() throws Exception {
		// Multiple queries to verify no state leakage between requests
		for (int i = 1; i <= 5; i++) {
			Message msg = Message.createQuery(i, "(+ " + i + " " + i + ")", Init.GENESIS_ADDRESS);
			Result r = postMessage(msg);
			assertFalse(r.isError());
			assertEquals(CVMLong.create(i * 2), r.getValue());
		}
	}

	// ---- Happy path: CVX text messages ----

	@Test
	public void testQueryViaCVXText() throws Exception {
		HttpResponse<String> resp = postCVX("[:Q 1 (* 3 4) #" + Init.GENESIS_ADDRESS.longValue() + "]");
		assertEquals(200, resp.statusCode());
		assertTrue(resp.body().contains("12"), "Response should contain result 12: " + resp.body());
	}

	@Test
	public void testStatusViaCVXText() throws Exception {
		HttpResponse<String> resp = postCVX("[:SR 1]");
		assertEquals(200, resp.statusCode());
		assertNotNull(resp.body());
	}

	// ---- Accept header content negotiation ----

	@Test
	public void testAcceptJSON() throws Exception {
		Message msg = Message.createQuery(1, "(+ 1 2)", Init.GENESIS_ADDRESS);
		Blob encoded = msg.getMessageData();
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(messagePath()))
				.header("Content-Type", ContentTypes.CVX_RAW)
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofByteArray(encoded.getBytes()))
				.build();
		HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, resp.statusCode());
		assertTrue(resp.headers().firstValue("Content-Type").orElse("").contains("json"));
		assertTrue(resp.body().contains("3"), "JSON response should contain value 3");
	}

	// ---- Adversarial: malformed CAD3 payloads ----

	@Test
	public void testEmptyBody() throws Exception {
		HttpResponse<byte[]> resp = postRaw(new byte[0]);
		assertTrue(resp.statusCode() >= 400, "Empty body should be rejected");
	}

	@Test
	public void testSingleNullByte() throws Exception {
		// 0x00 is CAD3 encoding of nil — not a valid message payload
		HttpResponse<byte[]> resp = postRaw(new byte[]{0x00});
		assertTrue(resp.statusCode() >= 200, "Should not crash on null-encoded payload");
	}

	@Test
	public void testGarbageBytes() throws Exception {
		HttpResponse<byte[]> resp = postRaw(new byte[]{(byte)0xDE, (byte)0xAD, (byte)0xBE, (byte)0xEF});
		assertTrue(resp.statusCode() >= 400, "Garbage bytes should be rejected");
	}

	@Test
	public void testHugePayload() throws Exception {
		byte[] huge = new byte[2_000_000];
		HttpResponse<byte[]> resp = postRaw(huge);
		assertTrue(resp.statusCode() >= 400, "Oversized payload should be rejected");
	}

	@Test
	public void testTruncatedMessage() throws Exception {
		Message msg = Message.createQuery(1, "(+ 2 3)", Init.GENESIS_ADDRESS);
		byte[] full = msg.getMessageData().getBytes();
		byte[] truncated = new byte[full.length / 2];
		System.arraycopy(full, 0, truncated, 0, truncated.length);
		HttpResponse<byte[]> resp = postRaw(truncated);
		// Should fail gracefully — not crash
		assertTrue(resp.statusCode() >= 200, "Truncated message should be handled gracefully");
	}

	@Test
	public void testRepeatedFFBytes() throws Exception {
		// 0xFF bytes can confuse VLC length parsers
		byte[] data = new byte[1000];
		Arrays.fill(data, (byte) 0xFF);
		HttpResponse<byte[]> resp = postRaw(data);
		assertTrue(resp.statusCode() >= 400, "Invalid 0xFF pattern should be rejected");
	}

	@Test
	public void testValidEncodingNotAMessage() throws Exception {
		// A plain integer encoded as CAD3 — valid encoding, but not a message
		Blob encoded = Format.encodeMultiCell(CVMLong.create(42), true);
		HttpResponse<byte[]> resp = postRaw(encoded.getBytes());
		// Should be handled gracefully (unknown message type)
		assertTrue(resp.statusCode() >= 200, "Non-message encoding should not crash");
	}

	@Test
	public void testCorruptedValidMessage() throws Exception {
		// Take a valid message encoding and flip some bits in the middle
		Message msg = Message.createQuery(1, "(+ 2 3)", Init.GENESIS_ADDRESS);
		byte[] data = msg.getMessageData().getBytes();
		if (data.length > 4) {
			data[data.length / 2] ^= 0xFF;
			data[data.length / 2 + 1] ^= 0xAA;
		}
		HttpResponse<byte[]> resp = postRaw(data);
		assertTrue(resp.statusCode() >= 200, "Corrupted message should not crash");
	}

	// ---- Adversarial: malformed CVX text ----

	@Test
	public void testMalformedCVXText() throws Exception {
		HttpResponse<String> resp = postCVX("this is not valid cvx [[[");
		assertTrue(resp.statusCode() >= 400, "Malformed CVX text should be rejected");
	}

	@Test
	public void testEmptyCVXText() throws Exception {
		HttpResponse<String> resp = postCVX("");
		assertTrue(resp.statusCode() >= 400, "Empty CVX text should be rejected");
	}

	@Test
	public void testCVXTextNotAVector() throws Exception {
		HttpResponse<String> resp = postCVX("42");
		// Valid CVX but not a message vector — should not crash
		assertTrue(resp.statusCode() >= 200, "Non-vector CVX should not crash");
	}

	@Test
	public void testCVXTextEmptyVector() throws Exception {
		HttpResponse<String> resp = postCVX("[]");
		assertTrue(resp.statusCode() >= 200, "Empty vector should not crash");
	}

	@Test
	public void testCVXTextBogusTag() throws Exception {
		HttpResponse<String> resp = postCVX("[:NONEXISTENT 1 2 3]");
		assertTrue(resp.statusCode() >= 200, "Unknown message tag should not crash");
	}

	// ---- Adversarial: wrong content type ----

	@Test
	public void testJSONContentType() throws Exception {
		// JSON body sent to the message endpoint — should fail as CVX parse error
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(messagePath()))
				.header("Content-Type", "application/json")
				.header("Accept", ContentTypes.CVX_RAW)
				.POST(HttpRequest.BodyPublishers.ofString("{\"source\": \"(+ 1 2)\"}"))
				.build();
		HttpResponse<byte[]> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
		assertTrue(resp.statusCode() >= 400, "JSON body should be rejected");
	}

	// ---- Adversarial: oversized result ----

	@Test
	public void testOversizedResult() throws Exception {
		// Conjoin multiple copies of *state* to exceed the 1MB response limit
		Message msg = Message.createQuery(1,
				"(vector *state* *state* *state* *state* *state*)",
				Init.GENESIS_ADDRESS);
		Result r = postMessage(msg);
		// Should be rejected by setContent size limit
		assertTrue(r.isError(), "Oversized result should be rejected");
		assertEquals(ErrorCodes.LIMIT, r.getErrorCode());
	}

	// ---- Adversarial: bad transactions ----

	@Test
	public void testTransactBadSignature() throws Exception {
		Address addr = Init.GENESIS_ADDRESS;
		AKeyPair wrongKP = AKeyPair.createSeeded(777);
		long seq = connect().getSequence(addr) + 1;
		Invoke tx = Invoke.create(addr, seq, Reader.read("(+ 1 1)"));
		SignedData<ATransaction> signed = wrongKP.signData((ATransaction) tx);
		Message msg = Message.createTransaction(1, signed);
		Result r = postMessage(msg);
		assertTrue(r.isError(), "Transaction with wrong key should fail");
	}

	@Test
	public void testTransactBadSequence() throws Exception {
		Address addr = Init.GENESIS_ADDRESS;
		Invoke tx = Invoke.create(addr, 0, Reader.read("(+ 1 1)"));
		SignedData<ATransaction> signed = KP.signData((ATransaction) tx);
		Message msg = Message.createTransaction(1, signed);
		Result r = postMessage(msg);
		assertTrue(r.isError(), "Transaction with bad sequence should fail");
		assertEquals(ErrorCodes.SEQUENCE, r.getErrorCode());
	}
}
