package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.util.JSON;
import convex.restapi.mcp.McpServer;
import io.javalin.Javalin;
import io.javalin.http.Context;

/**
 * An embedder's MCP endpoint that reads the request body before handing the request
 * to {@link McpServer} still has it handled.
 */
public class McpEmbedderBodyTest {

	private static final String PING="{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":1}";

	/** Reads the body, as an embedder inspecting a request might, then hands it on. */
	private static class PreReadingMcp extends McpServer {
		volatile String seen;

		PreReadingMcp() {
			super(Maps.of("name","embedder","version","1"));
		}

		@Override
		protected void handlePost(Context ctx) {
			seen=ctx.body();
			super.handlePost(ctx);
		}
	}

	@Test
	public void testBodyReadBeforeHandler() throws IOException, InterruptedException {
		PreReadingMcp mcp=new PreReadingMcp();
		Javalin app=Javalin.create(config -> mcp.addRoutes(config.routes)).start(0);
		try {
			HttpResponse<String> response=post(app,PING);
			assertEquals(PING,mcp.seen);
			assertEquals(200,response.statusCode());
			AMap<ACell,ACell> reply=JSON.parse(response.body());
			assertNull(reply.get(Strings.create("error")));
			assertNotNull(reply.get(Strings.create("result")));
		} finally {
			app.stop();
		}
	}

	@Test
	public void testBodyReadBeforeHandlerStillLimited() throws IOException, InterruptedException {
		PreReadingMcp mcp=new PreReadingMcp();
		mcp.setMaxRequestBytes(PING.length()-1);
		Javalin app=Javalin.create(config -> mcp.addRoutes(config.routes)).start(0);
		try {
			assertEquals(413,post(app,PING).statusCode());
		} finally {
			app.stop();
		}
	}

	private static HttpResponse<String> post(Javalin app, String json) throws IOException, InterruptedException {
		HttpRequest request=HttpRequest.newBuilder(URI.create("http://localhost:"+app.port()+"/mcp"))
			.header("Content-Type","application/json")
			.header("Accept","application/json")
			.POST(HttpRequest.BodyPublishers.ofString(json))
			.build();
		try (HttpClient client=HttpClient.newHttpClient()) {
			return client.send(request,HttpResponse.BodyHandlers.ofString());
		}
	}
}
