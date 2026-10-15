package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import convex.api.ContentTypes;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Format;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;
import convex.core.util.JSON;
import convex.java.ConvexHTTP;

public class SimulationAPITest extends ARESTTest {
	@Test public void testZeroBalance() throws Exception {
		try (ConvexHTTP client = newClient()) {
			Address empty = client.createAccountSync(CLIENT_KP.getAccountKey());
			String body = JSON.toString(Maps.of("address",empty,"source","*balance*"));
			AMap<AString,ACell> query = result(post(API_PATH+"/query",body));
			assertEquals(CVMLong.ZERO,query.getIn("value"));
			assertNull(query.getIn("errorCode"));
			AMap<AString,ACell> simulation = result(post(API_PATH+"/simulate",body));
			assertEquals("JUICE",simulation.getIn("errorCode").toString());
			assertEquals(0L,client.getBalance(empty));
			assertEquals(0L,client.getSequence(empty));
		}
	}

	@Test public void testAccountingWithoutCommitting() throws Exception {
		try (ConvexHTTP client = newClient()) {
			Address address = client.getAddress();
			long sequence = client.getSequence();
			long balance = client.getBalance();
			String source = "(def simulation-value 23)";
			AMap<AString,ACell> request = Maps.of("address",address,"source",source);
			AMap<AString,ACell> simulation = result(post(API_PATH+"/simulate",JSON.toString(request)));
			assertNull(simulation.getIn("errorCode"));
			assertEquals(CVMLong.create(23),simulation.getIn("value"));
			assertNotNull(simulation.getIn("info","fees"));
			assertNotNull(simulation.getIn("info","juice"));
			assertEquals(balance,client.getBalance());
			assertEquals(sequence,client.getSequence(address));
			assertTrue(client.querySync("simulation-value").isError());

			Result actual = client.transactSync(source);
			assertSucceeded(actual);
			assertEquals(actual.getIn(Keywords.INFO,Keywords.FEES),simulation.getIn("info","fees"));
			assertEquals(actual.getIn(Keywords.INFO,Keywords.JUICE),simulation.getIn("info","juice"));
		}
	}

	@Test public void testUnsignedAndUnvalidatedSignedPayloads() throws Exception {
		try (ConvexHTTP client = newClient()) {
			long balance = client.getBalance();
			long sequence = client.getSequence();
			// Deliberately stale sequence and a different signing key are accepted.
			Invoke transaction = Invoke.create(client.getAddress(),0,"(+ 2 3)");
			AMap<AString,ACell> unsigned = binaryResult(transaction);
			AMap<AString,ACell> signed = binaryResult(AKeyPair.createSeeded(7654321).signData(transaction));
			AMap<AString,ACell> json = result(post(API_PATH+"/simulate",JSON.toString(Maps.of(
					"address",client.getAddress(),"source","(+ 2 3)","sequence",0))));
			for (AMap<AString,ACell> simulation : java.util.List.of(unsigned,signed,json)) {
				assertNull(simulation.getIn("errorCode"));
				assertEquals(CVMLong.create(5),simulation.getIn("value"));
				assertEquals(unsigned.getIn("info","fees"),simulation.getIn("info","fees"));
			}
			assertEquals(balance,client.getBalance());
			assertEquals(sequence,client.getSequence(client.getAddress()));
		}
	}

	@Test public void testExecutionLimitAndFunds() throws Exception {
		try (ConvexHTTP client = newClient()) {
			AMap<AString,ACell> loop = result(post(API_PATH+"/simulate",JSON.toString(Maps.of(
					"address",client.getAddress(),"source","(loop [] (recur))"))));
			assertEquals("JUICE",loop.getIn("errorCode").toString());
			assertEquals(CVMLong.create(server.getRESTConfig().getMaxQueryJuice()),loop.getIn("info","juice"));
			AMap<AString,ACell> funds = result(post(API_PATH+"/simulate",JSON.toString(Maps.of(
					"address",client.getAddress(),"source","(transfer #11 1000000000000)"))));
			assertEquals("FUNDS",funds.getIn("errorCode").toString());
		}
	}

	@Test public void testInvalidRequests() throws Exception {
		for (String body : new String[] {
				"{}", "{\"source\":\"1\"}", "{\"address\":\"#11\"}",
				"{\"address\":\"#11\",\"source\":42}",
				"{\"address\":\"#11\",\"source\":\"(\"}",
				"{\"address\":\"#11\",\"source\":\"1\",\"sequence\":-1}",
				"{\"address\":\"#11\",\"source\":\"1\",\"sequence\":1.5}",
				"{\"address\":\"#11\",\"source\":\"1\",\"sequence\":9223372036854775808}"}) {
			assertEquals(400,post(API_PATH+"/simulate",body).statusCode(),body);
		}
		AMap<AString,ACell> missing = result(post(API_PATH+"/simulate",
				"{\"address\":\"#999999999\",\"source\":\"1\"}"));
		assertEquals("NOBODY",missing.getIn("errorCode").toString());
		assertEquals(400,postBinary(CVMLong.ONE).statusCode());
	}

	private static AMap<AString,ACell> binaryResult(ACell payload) throws Exception {
		return result(postBinary(payload));
	}

	private static HttpResponse<String> postBinary(ACell payload) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(API_PATH+"/simulate"))
				.header("Content-Type",ContentTypes.CVX_RAW)
				.header("Accept","application/json")
				.POST(HttpRequest.BodyPublishers.ofByteArray(Format.encodeMultiCell(payload,true).getBytes()))
				.build();
		return httpClient.send(request,HttpResponse.BodyHandlers.ofString());
	}

	private static AMap<AString,ACell> result(HttpResponse<String> response) {
		assertEquals(200,response.statusCode(),response.body());
		return JSON.parse(response.body());
	}
}
