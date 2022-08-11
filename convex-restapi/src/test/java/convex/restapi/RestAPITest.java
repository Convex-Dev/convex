package convex.restapi;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.util.Utils;
import convex.peer.API;
import convex.peer.Server;

// from convex-peer test java package


public class RestAPITest {
 
	static final int PORT = 9900;

	private static Server server;
	static Address ADDRESS;
	static AKeyPair KEYPAIR = null;
	static Convex CONVEX=null;
	static Address HERO=null;

	private static final Logger log = LoggerFactory.getLogger(RestAPITest.class.getName());
    
	@BeforeAll
	public static void init() {
		server=API.launchPeer();
		synchronized(server) {
			try {
				KEYPAIR=server.getKeyPair();
				HERO=server.getPeerController();
				CONVEX=Convex.connect(server, HERO, KEYPAIR);
				ADDRESS=CONVEX.createAccountSync(KEYPAIR.getAccountKey());
				CONVEX.transfer(ADDRESS, 1000000000L).get(1000,TimeUnit.MILLISECONDS);
				APIServer.start(PORT, CONVEX);
				Thread.sleep(200);
			} catch (Throwable e) {
				e.printStackTrace();
				throw Utils.sneakyThrow(e);
			}
		}
	}

	@Test
	public void getHeroAccount() throws IOException, ParseException {
		State state = server.getPeer().getConsensusState();
		AccountStatus status = state.getAccount(HERO);
		// System.out.println("account status " + status);

		String url = "http://127.0.0.1:" + PORT + "/api/v1/accounts/" + HERO.longValue();
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
		assertEquals(HERO.longValue(), result.get("address"));
		assertEquals(status.getMemoryUsage(), result.get("memorySize"));
		assertEquals(status.getMemory(), result.get("allowance"));
		assertEquals(status.getBalance(), result.get("balance"));
		assertEquals("user", result.get("type"));
		assertEquals(false, result.get("isActor"));
		assertEquals(false, result.get("isLibrary"));
		assertEquals(KEYPAIR.getAccountKey().toString(), result.get("accountKey"));
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
