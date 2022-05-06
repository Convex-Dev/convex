package convex.restapi;

/**
 * Tests for a baisc REST API calls
*/


import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.api.ConvexLocal;
import convex.core.crypto.AKeyPair;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.State;
import convex.core.util.Utils;

// from convex-peer test java package
import convex.peer.TestNetwork;


public class RestAPITest {

	static Address ADDRESS;
	static final AKeyPair KEYPAIR = AKeyPair.generate();
	static final int PORT = 9900;

	private static TestNetwork network;

	private static final Logger log = LoggerFactory.getLogger(RestAPITest.class.getName());

	@BeforeAll
	public static void init() {
		network = TestNetwork.getInstance();
		synchronized(network.SERVER) {
			try {
				ADDRESS=network.CONVEX.createAccountSync(KEYPAIR.getAccountKey());
				network.CONVEX.transfer(ADDRESS, 1000000000L).get(1000,TimeUnit.MILLISECONDS);
				ConvexLocal convex = Convex.connect(network.SERVER, ADDRESS, KEYPAIR);
				APIServer.start(PORT, convex);
				Thread.sleep(200);
			} catch (Throwable e) {
				e.printStackTrace();
				throw Utils.sneakyThrow(e);
			}
		}
	}

	@Test
	public void getHeroAccount() throws IOException, ParseException {
		State state = network.SERVER.getPeer().getConsensusState();
		AccountStatus status = state.getAccount(network.HERO);
		// System.out.println("account status " + status);

		String url = "http://127.0.0.1:" + PORT + "/api/v1/accounts/" + network.HERO.longValue();
		log.info("getHeroAccount - " + url);
		HttpUriRequest request = new HttpGet(url);
		request.addHeader("accept", "application/json");
		HttpResponse response = HttpClientBuilder.create().build().execute(request);
		assertEquals(200, response.getStatusLine().getStatusCode());

		String mimeType = ContentType.getOrDefault(response.getEntity()).getMimeType();
		assertEquals("application/json", mimeType);

		String responseBody = EntityUtils.toString(response.getEntity());
		log.info("response body: " + responseBody);
		JSONParser parser = new JSONParser();
		JSONObject parsedObject = (JSONObject) parser.parse(responseBody);
		JSONObject result = new JSONObject(parsedObject);
		assertEquals(status.getSequence(), result.get("sequence"));
		assertEquals(network.HERO.longValue(), result.get("address"));
		assertEquals(status.getMemoryUsage(), result.get("memorySize"));
		assertEquals(status.getMemory(), result.get("allowance"));
		assertEquals(status.getBalance(), result.get("balance"));
		assertEquals("user", result.get("type"));
		assertEquals(false, result.get("isActor"));
		assertEquals(false, result.get("isLibrary"));
		assertEquals(network.HERO_KEYPAIR.getAccountKey().toString(), result.get("accountKey"));
	}

	@Test
	public void getAccountFailAccountNotFound() throws IOException {
		String url = "http://127.0.0.1:" + PORT + "/api/v1/accounts/100000" ;
		log.info("getAccountFailAccountNotFound - " + url);
		HttpUriRequest request = new HttpGet(url);
		request.addHeader("accept", "application/json");
		HttpResponse response = HttpClientBuilder.create().build().execute(request);
		assertEquals(404, response.getStatusLine().getStatusCode());
		String responseBody = EntityUtils.toString(response.getEntity());
		log.info("response body " + responseBody);
	}

	@Test
	public void getAccountFailBadAddress() throws IOException {
		String url = "http://127.0.0.1:" + PORT + "/api/v1/accounts/bad-address" ;
		log.info("getAccountFailBadAddress - " + url);
		HttpUriRequest request = new HttpGet(url);
		request.addHeader("accept", "application/json");
		HttpResponse response = HttpClientBuilder.create().build().execute(request);
		assertEquals(500, response.getStatusLine().getStatusCode());
		String responseBody = EntityUtils.toString(response.getEntity());
		log.info("response body " + responseBody);
	}

}
