package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Context;
import convex.core.cvm.Keywords;
import convex.core.cvm.State;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Keyword;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.init.Init;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.util.JSON;
import convex.peer.API;
import convex.peer.Server;
import convex.restapi.RESTServer;

class DIDNamedAPITest {

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	private static Server peerServer;
	private static RESTServer restServer;
	private static String host;

	@BeforeAll
	static void launchServer() throws Exception {
		AKeyPair peerKey = AKeyPair.createSeeded(618_001);
		State state = Init.createTestState(List.of(peerKey.getAccountKey()));
		Context context = Context.create(state, Init.GOVERNANCE_ADDRESS);

		context = eval(context,
				"(*registry*/create 'convex.did-http-account "
						+ Init.GENESIS_ADDRESS + " " + Init.ADMIN_ADDRESS + ")");

		context = eval(context, "(call @convex.did (create))");
		CVMLong activeID = (CVMLong) context.getResult();
		AString stored = Strings.create("""
				{"service":[{"id":"#api","type":"API","serviceEndpoint":"https://example.com/api"}]}
				""");
		context = eval(context,
				"(call @convex.did (update " + activeID + " " + RT.print(stored) + "))");
		context = eval(context,
				"(*registry*/create 'convex.did-http-registry [@convex.did "
						+ activeID + "] " + Init.ADMIN_ADDRESS + ")");

		context = eval(context, "(call @convex.did (create))");
		CVMLong deactivatedID = (CVMLong) context.getResult();
		context = eval(context,
				"(call @convex.did (deactivate " + deactivatedID + "))");
		context = eval(context,
				"(*registry*/create 'convex.did-http-deactivated [@convex.did "
						+ deactivatedID + "] " + Init.ADMIN_ADDRESS + ")");

		Map<Keyword, Object> config = new HashMap<>();
		config.put(Keywords.KEYPAIR, peerKey);
		config.put(Keywords.STATE, context.getState());
		config.put(Keywords.PORT, 0);
		config.put(Keywords.PERSIST, false);
		peerServer = API.launchPeer(config);

		restServer = RESTServer.create(peerServer);
		restServer.start(0);
		host = "http://localhost:" + restServer.getPort();
	}

	@AfterAll
	static void closeServer() {
		if (restServer != null) restServer.close();
		if (peerServer != null) peerServer.close();
	}

	@Test
	void servesNamedAccountDocument() throws Exception {
		HttpResponse<String> response = get("/convex.did-http-account/did.json");

		assertEquals(200, response.statusCode());
		AMap<AString, ACell> document = parseMap(response.body());
		assertEquals("did:convex:" + Init.ADMIN_ADDRESS.longValue(),
				document.get(RT.cvm("controller")).toString());
		assertNotNull(document.get(RT.cvm("verificationMethod")));
		assertNotNull(document.get(RT.cvm("alsoKnownAs")));
	}

	@Test
	void servesRegistryBackedDocument() throws Exception {
		HttpResponse<String> response = get("/convex.did-http-registry/did.json");

		assertEquals(200, response.statusCode());
		AMap<AString, ACell> document = parseMap(response.body());
		assertEquals("did:convex:" + Init.ADMIN_ADDRESS.longValue(),
				document.get(RT.cvm("controller")).toString());
		assertNotNull(document.get(RT.cvm("service")));
	}

	@Test
	void returnsResolutionMetadataForDeactivatedDID() throws Exception {
		HttpResponse<String> response = get("/convex.did-http-deactivated/did.json");

		assertEquals(410, response.statusCode());
		assertEquals("application/did-resolution",
				response.headers().firstValue("Content-Type").orElseThrow());

		AMap<AString, ACell> result = parseMap(response.body());
		assertNull(result.get(RT.cvm("didDocument")));
		AMap<AString, ACell> metadata = RT.castMap(result.get(RT.cvm("didDocumentMetadata")));
		assertNotNull(metadata);
		assertEquals(RT.cvm(true), metadata.get(RT.cvm("deactivated")));
		assertNotNull(metadata.get(RT.cvm("created")));
		assertNotNull(metadata.get(RT.cvm("updated")));
	}

	@Test
	void missingNamedDIDIsNotFound() throws Exception {
		HttpResponse<String> response = get("/convex.did-http-missing/did.json");

		assertEquals(404, response.statusCode());
		assertFalse(response.body().isEmpty());
	}

	private static HttpResponse<String> get(String path) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(host + path)).GET().build();
		return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> parseMap(String json) {
		return (AMap<AString, ACell>) JSON.parse(json);
	}

	private static Context eval(Context context, String source) {
		Context result = context.eval(Reader.read(source));
		if (result.isExceptional()) {
			throw new AssertionError("Unexpected CVM error for " + source + ": " + result.getValue());
		}
		return result;
	}
}
