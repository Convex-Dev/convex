package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import convex.auth.did.DID;
import convex.auth.jwt.JWT;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.peer.API;
import convex.peer.Server;
import convex.peer.auth.PeerAuth;
import convex.restapi.RESTConfig;
import convex.restapi.RESTServer;

/**
 * Runtime configuration tests using a real Peer and HTTP server.
 *
 * <p>Each fixture crosses the production compatibility boundary:
 * {@link RESTConfig#toLegacy()} attaches the typed policy to the Peer launch
 * map, and {@link RESTServer#create(Server)} recovers it. This guards against
 * configuration accessors that work in isolation but are ignored at runtime.</p>
 */
class RESTRuntimeConfigTest {

	private static final HttpClient CLIENT=HttpClient.newHttpClient();

	@Test
	void publicAccessIsOpenByDefault() throws Exception {
		try (RunningServer running=launch("{mcp:{enabled:false}}")) {
			assertTrue(running.rest().getRESTConfig().isPublicAccess());
			assertEquals(200,get(running.url("/api/v1/status"),null).statusCode());
			assertEquals(404,postCvx(running.url("/api/v1/message"),"[:SR 1]").statusCode());
		}
	}

	@Test
	void publicPeerMayExplicitlyExposeProtocolMessagesOverHttp() throws Exception {
		try (RunningServer running=launch("{rest:{messageEndpoint:true},mcp:{enabled:false}}")) {
			assertTrue(running.rest().getRESTConfig().isPublicAccess());
			assertTrue(running.rest().getRESTConfig().isMessageEndpointEnabled());
			assertEquals(200,postCvx(running.url("/api/v1/message"),"[:SR 1]").statusCode());
		}
	}

	@Test
	void privateAccessRequiresValidBearerToken() throws Exception {
		try (RunningServer running=launch("{auth:{publicAccess:false}}")) {
			assertEquals(401,get(running.url("/api/v1/status"),null).statusCode());
			assertEquals(401,get(running.url("/api/v1/status"),"not-a-valid-token").statusCode());
			assertEquals(401,post(running.url("/mcp"),ping(),null,null).statusCode());

			// DID metadata stays reachable so clients can discover the identity that
			// verifies tokens before they have authenticated.
			assertEquals(200,get(running.url("/.well-known/did.json"),null).statusCode());

			AString token=new PeerAuth(running.peer().getKeyPair())
					.issuePeerToken(Strings.create("did:web:client.example"),300);
			assertEquals(200,get(running.url("/api/v1/status"),token.toString()).statusCode());
		}
	}

	@Test
	void disablingMcpRemovesRoutesAndDiscovery() throws Exception {
		try (RunningServer running=launch("{mcp:{enabled:false,signing:true,elevated:true}}")) {
			assertNull(running.rest().getMcpAPI());
			assertNull(running.rest().getSigningService());
			assertNull(running.rest().getConfirmationService());
			assertEquals(404,post(running.url("/mcp"),ping(),null,null).statusCode());
			assertEquals(404,get(running.url("/.well-known/mcp"),null).statusCode());
			assertEquals(404,get(running.url("/explorer/mcp"),null).statusCode());
			assertFalse(get(running.url("/"),null).body().contains("MCP Endpoint"));
			assertFalse(get(running.url("/llms.txt"),null).body().contains("MCP Endpoint"));
		}
	}

	@Test
	void signingElevatedAndPerToolPoliciesBoundRegistration() throws Exception {
		String config="""
			{mcp:{signing:true,elevated:false,tools:{query:{enabled:false}}}}
			""";
		try (RunningServer running=launch(config)) {
			HttpResponse<String> response=post(running.url("/mcp"),toolsList(),null,null);
			assertEquals(200,response.statusCode());
			AVector<AMap<AString,ACell>> tools=toolList(response.body());
			assertFalse(hasTool(tools,"query"));
			assertTrue(hasTool(tools,"signingSign"));
			assertFalse(hasTool(tools,"signingExportKey"));
			assertNull(running.rest().getConfirmationService());
		}
	}

	@Test
	void elevatedCannotEnableSigningByItself() throws Exception {
		try (RunningServer running=launch("{mcp:{signing:false,elevated:true}}")) {
			AVector<AMap<AString,ACell>> tools=toolList(
					post(running.url("/mcp"),toolsList(),null,null).body());
			assertFalse(hasTool(tools,"signingSign"));
			assertFalse(hasTool(tools,"signingExportKey"));
			assertNull(running.rest().getSigningService());
			assertNull(running.rest().getConfirmationService());
		}
	}

	@Test
	void administrationRoutesAreOptIn() throws Exception {
		try (RunningServer running=launch("{mcp:{enabled:false}}")) {
			assertFalse(running.rest().getRESTConfig().isAdminEnabled());
			assertEquals(404,post(running.url("/api/v1/peer/shutdown"),"{}",null,null).statusCode());
			assertEquals(200,get(running.url("/api/v1/status"),null).statusCode());
		}
	}

	@Test
	void administrationRequiresStableConfiguredVenueIdentity() throws Exception {
		RESTConfig config=RESTConfig.parse("{rest:{admin:true},mcp:{enabled:false}}");
		var launchConfig=config.toLegacy();
		launchConfig.put(Keywords.KEYPAIR,AKeyPair.generate());
		Server peer=API.launchPeer(launchConfig);
		try {
			org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				()->RESTServer.create(peer));
		} finally {
			peer.close();
		}
	}

	@Test
	void administrationRejectsMissingWrongAndForgedCredentials() throws Exception {
		String config="""
			{rest:{baseUrl:"http://localhost",admin:{enabled:true}},mcp:{enabled:false}}
			""";
		try (RunningServer running=launch(config)) {
			String shutdown=running.url("/api/v1/peer/shutdown");
			assertEquals(401,post(shutdown,"{}",null,null).statusCode());

			AKeyPair attacker=AKeyPair.generate();
			AString attackerToken=selfIssuedToken(attacker,"did:web:localhost");
			assertEquals(403,postAdmin(shutdown,attackerToken.toString(),
				"127.0.0.1","https").statusCode(),
				"forwarded client and protocol headers must not grant administrator authority");
			AString operationalToken=selfIssuedToken(running.peer().getKeyPair(),"did:web:localhost");
			assertEquals(403,postAdmin(shutdown,operationalToken.toString(),
				"203.0.113.7",null).statusCode(),
				"proxy metadata must not make cleartext external traffic look like direct loopback access");

			AString wrongAudience=selfIssuedToken(running.peer().getKeyPair(),"did:web:other.example");
			assertEquals(401,postAdmin(shutdown,wrongAudience.toString(),null,null).statusCode());
			assertTrue(running.peer().isRunning());
		}
	}

	@Test
	void operationalKeyMayShutDownLocalPeerWithVenueAudience() throws Exception {
		String config="""
			{rest:{baseUrl:"http://localhost",admin:{enabled:true}},mcp:{enabled:false}}
			""";
		try (RunningServer running=launch(config)) {
			AString token=selfIssuedToken(running.peer().getKeyPair(),"did:web:localhost");
			HttpResponse<String> response=post(
				running.url("/api/v1/peer/shutdown"),"{}",token.toString(),null);
			assertEquals(200,response.statusCode());
			assertFalse(running.peer().isRunning());
		}
	}

	@Test
	void configuredVenueDIDIgnoresForgedForwardedHost() throws Exception {
		try (RunningServer running=launch(
				"{rest:{baseUrl:\"https://venue.example.com\"},mcp:{enabled:false}}")) {
			HttpRequest request=HttpRequest.newBuilder(URI.create(running.url("/.well-known/did.json")))
				.header("X-Forwarded-Host","attacker.example")
				.GET().build();
			HttpResponse<String> response=CLIENT.send(request,HttpResponse.BodyHandlers.ofString());
			assertEquals(200,response.statusCode());
			AMap<AString,ACell> doc=RT.castMap(JSON.parse(response.body()));
			assertEquals(Strings.create("did:web:venue.example.com"),doc.get(Strings.create("id")));
		}
	}

	@Test
	void corsAllowListIsNotOverriddenByWildcardHeaders() throws Exception {
		try (RunningServer running=launch("{rest:{cors:[\"https://app.example\"]},mcp:{enabled:false}}")) {
			HttpResponse<String> allowed=getWithOrigin(running.url("/api/v1/status"),"https://app.example");
			assertEquals("https://app.example",allowed.headers()
					.firstValue("Access-Control-Allow-Origin").orElse(null));

			HttpResponse<String> denied=getWithOrigin(running.url("/api/v1/status"),"https://evil.example");
			assertTrue(denied.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
		}
	}

	private static RunningServer launch(String json5) throws Exception {
		RESTConfig config=RESTConfig.parse(json5);
		var launchConfig=config.toLegacy();
		launchConfig.put(Keywords.KEYPAIR,AKeyPair.generate());
		Server peer=API.launchPeer(launchConfig);
		RESTServer rest=RESTServer.create(peer);
		try {
			rest.start(0);
			assertNotNull(rest.getRESTConfig());
			return new RunningServer(peer,rest);
		} catch (RuntimeException | Error t) {
			rest.close();
			peer.close();
			throw t;
		}
	}

	private static HttpResponse<String> get(String url,String bearer) throws Exception {
		HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(url)).GET();
		if (bearer!=null) request.header("Authorization","Bearer "+bearer);
		return CLIENT.send(request.build(),HttpResponse.BodyHandlers.ofString());
	}

	private static HttpResponse<String> getWithOrigin(String url,String origin) throws Exception {
		HttpRequest request=HttpRequest.newBuilder(URI.create(url)).header("Origin",origin).GET().build();
		return CLIENT.send(request,HttpResponse.BodyHandlers.ofString());
	}

	private static HttpResponse<String> post(String url,String body,String bearer,String origin) throws Exception {
		HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(url))
				.header("Content-Type","application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body));
		if (bearer!=null) request.header("Authorization","Bearer "+bearer);
		if (origin!=null) request.header("Origin",origin);
		return CLIENT.send(request.build(),HttpResponse.BodyHandlers.ofString());
	}

	private static HttpResponse<String> postCvx(String url,String body) throws Exception {
		HttpRequest request=HttpRequest.newBuilder(URI.create(url))
				.header("Content-Type","application/cvx")
				.header("Accept","application/cvx")
				.POST(HttpRequest.BodyPublishers.ofString(body)).build();
		return CLIENT.send(request,HttpResponse.BodyHandlers.ofString());
	}

	private static HttpResponse<String> postAdmin(String url,String bearer,String forwardedFor,
			String forwardedProto) throws Exception {
		HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(url))
			.header("Content-Type","application/json")
			.header("Authorization","Bearer "+bearer)
			.POST(HttpRequest.BodyPublishers.ofString("{}"));
		if (forwardedFor!=null) request.header("X-Forwarded-For",forwardedFor);
		if (forwardedProto!=null) request.header("X-Forwarded-Proto",forwardedProto);
		return CLIENT.send(request.build(),HttpResponse.BodyHandlers.ofString());
	}

	private static AString selfIssuedToken(AKeyPair signer,String audience) {
		long now=System.currentTimeMillis()/1000;
		AString did=DID.forKey(signer.getAccountKey());
		AMap<AString,ACell> claims=Maps.of(
			JWT.SUB,did,
			JWT.ISS,did,
			JWT.AUD,Strings.create(audience),
			JWT.IAT,now,
			JWT.EXP,now+300
		);
		return JWT.signPublic(claims,signer);
	}

	private static String ping() {
		return "{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":1}";
	}

	private static String toolsList() {
		return "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"params\":{},\"id\":1}";
	}

	@SuppressWarnings("unchecked")
	private static AVector<AMap<AString,ACell>> toolList(String response) {
		AMap<AString,ACell> envelope=RT.castMap(JSON.parse(response));
		return (AVector<AMap<AString,ACell>>) RT.getIn(envelope,"result","tools");
	}

	private static boolean hasTool(AVector<AMap<AString,ACell>> tools,String name) {
		for (long i=0;i<tools.count();i++) {
			if (Strings.create(name).equals(tools.get(i).get(Strings.create("name")))) return true;
		}
		return false;
	}

	private record RunningServer(Server peer,RESTServer rest) implements AutoCloseable {
		String url(String path) {
			return "http://localhost:"+rest.getPort()+path;
		}

		@Override
		public void close() {
			rest.close();
			peer.close();
		}
	}
}
