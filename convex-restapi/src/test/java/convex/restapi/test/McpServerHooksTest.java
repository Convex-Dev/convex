package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.restapi.mcp.McpProtocol;
import convex.restapi.mcp.McpServer;
import io.javalin.Javalin;
import io.javalin.http.Context;

/**
 * An embedder answers single requests and acts on notifications through
 * {@link McpServer}'s hooks, without replacing its POST handling.
 */
public class McpServerHooksTest {

	private static final String PING="{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":1}";
	private static final String CALL="{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"id\":7,\"params\":{\"name\":\"slow\",\"arguments\":{}}}";
	private static final String CANCELLED="{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":7}}";

	private static final String SSE="text/event-stream";
	private static final String JSON_ONLY="application/json";
	private static final String JSON_OR_SSE="application/json, text/event-stream";

	/** What the tools/call override does after it starts its stream. */
	private enum Stream { COMPLETE, FAIL_AFTER, FAIL_BEFORE }

	/** Streams a tools/call as two SSE events; records notifications and the context they came with. */
	private static class HookedMcp extends McpServer {
		final List<String> notified=new CopyOnWriteArrayList<>();
		final Stream stream;
		/** The result Javalin would write after the handler, seen by an after-handler. */
		volatile String resultAfterHandler;

		HookedMcp(Stream stream) {
			super(Maps.of("name","hooks","version","1"));
			this.stream=stream;
		}

		@Override
		protected void handleRequest(Context ctx,AMap<?,?> request) {
			if (!"tools/call".equals(McpProtocol.getMethodName(request))) {
				super.handleRequest(ctx,request);
				return;
			}
			if (stream==Stream.FAIL_BEFORE) throw new IllegalStateException("failed before answering");
			ACell id=request.get(McpProtocol.FIELD_ID);
			StringBuilder events=new StringBuilder();
			McpProtocol.appendSseEvent(events,Maps.of("jsonrpc","2.0","method","notifications/progress"));
			McpProtocol.appendSseEvent(events,McpProtocol.maybeAttachId(McpProtocol.protocolResult(Maps.of("streamed",true)),id));
			try {
				McpProtocol.writeSseToServletResponse(ctx,events.toString());
			} catch (IOException e) {
				return; // the client has gone
			}
			if (stream==Stream.FAIL_AFTER) throw new IllegalStateException("failed after streaming");
		}

		@Override
		protected void handleNotification(Context ctx,AMap<?,?> notification) {
			boolean current=(ctx!=null)&&(ctx==McpServer.getCurrentContext());
			notified.add(McpProtocol.getMethodName(notification)+(current?"":" without its context"));
		}
	}

	@Test
	public void testRequestHookAnswersItself() throws Exception {
		HookedMcp mcp=new HookedMcp(Stream.COMPLETE);
		Javalin app=start(mcp);
		try {
			HttpResponse<String> response=post(app,CALL,JSON_OR_SSE);
			assertEquals(200,response.statusCode());
			assertTrue(contentType(response).startsWith(SSE));
			List<AMap<ACell,ACell>> events=events(response.body());
			assertEquals(2,events.size());
			assertEquals("notifications/progress",RT.str(events.get(0).get(Strings.create("method"))).toString());
			assertEquals(7L,RT.ensureLong(events.get(1).get(Strings.create("id"))).longValue());
			assertNotNull(events.get(1).get(Strings.create("result")));
		} finally {
			app.stop();
		}
	}

	@Test
	public void testRequestHookLeavesOtherRequestsToTheBase() throws Exception {
		HookedMcp mcp=new HookedMcp(Stream.COMPLETE);
		Javalin app=start(mcp);
		try {
			HttpResponse<String> json=post(app,PING,JSON_ONLY);
			assertEquals(200,json.statusCode());
			AMap<ACell,ACell> reply=JSON.parse(json.body());
			assertNotNull(reply.get(Strings.create("result")));

			HttpResponse<String> sse=post(app,PING,SSE);
			List<AMap<ACell,ACell>> events=events(sse.body());
			assertEquals(1,events.size());
			assertNotNull(events.get(0).get(Strings.create("result")));
		} finally {
			app.stop();
		}
	}

	/** Requests in a batch are answered together, so they never reach the single-request hook. */
	@Test
	public void testBatchedRequestsBypassTheRequestHook() throws Exception {
		HookedMcp mcp=new HookedMcp(Stream.COMPLETE);
		Javalin app=start(mcp);
		try {
			HttpResponse<String> response=post(app,"["+CALL+"]",JSON_ONLY);
			assertEquals(200,response.statusCode());
			AVector<AMap<ACell,ACell>> replies=JSON.parse(response.body());
			assertEquals(1,replies.count());
			assertNotNull(replies.get(0).get(Strings.create("error")),"the base has no tool to call");
		} finally {
			app.stop();
		}
	}

	@Test
	public void testNotificationHookAloneAndInBatch() throws Exception {
		HookedMcp mcp=new HookedMcp(Stream.COMPLETE);
		Javalin app=start(mcp);
		try {
			HttpResponse<String> alone=post(app,CANCELLED,JSON_ONLY);
			assertEquals(202,alone.statusCode());
			assertEquals(List.of("notifications/cancelled"),mcp.notified);

			HttpResponse<String> batch=post(app,"["+CANCELLED+","+PING+"]",JSON_ONLY);
			assertEquals(200,batch.statusCode());
			AVector<AMap<ACell,ACell>> replies=JSON.parse(batch.body());
			assertEquals(1,replies.count(),"only the request is answered");
			assertNotNull(replies.get(0).get(Strings.create("result")));
			assertEquals(List.of("notifications/cancelled","notifications/cancelled"),mcp.notified);
		} finally {
			app.stop();
		}
	}

	@Test
	public void testFailureBeforeAnsweringIsAnInternalError() throws Exception {
		HookedMcp mcp=new HookedMcp(Stream.FAIL_BEFORE);
		Javalin app=start(mcp);
		try {
			HttpResponse<String> response=post(app,CALL,JSON_OR_SSE);
			AMap<ACell,ACell> reply=JSON.parse(response.body());
			AMap<ACell,ACell> error=RT.ensureMap(reply.get(Strings.create("error")));
			assertNotNull(error);
			assertEquals(-32603L,RT.ensureLong(error.get(Strings.create("code"))).longValue());
		} finally {
			app.stop();
		}
	}

	@Test
	public void testFailureAfterStreamingAddsNothing() throws Exception {
		HookedMcp mcp=new HookedMcp(Stream.FAIL_AFTER);
		Javalin app=start(mcp);
		try {
			HttpResponse<String> response=post(app,CALL,JSON_OR_SSE);
			assertEquals(200,response.statusCode());
			assertTrue(contentType(response).startsWith(SSE));
			assertEquals(2,events(response.body()).size());
			assertFalse(response.body().contains("Internal error"),"nothing follows a stream that has started");
			assertNull(mcp.resultAfterHandler,"the base must not try to send a second answer");
		} finally {
			app.stop();
		}
	}

	private static Javalin start(HookedMcp mcp) {
		return Javalin.create(config -> {
			mcp.addRoutes(config.routes);
			config.routes.after("/mcp",ctx -> mcp.resultAfterHandler=ctx.result());
		}).start(0);
	}

	private static String contentType(HttpResponse<String> response) {
		return response.headers().firstValue("Content-Type").orElse("");
	}

	/** Parses each SSE event's data, failing on any line that is not part of an event. */
	private static List<AMap<ACell,ACell>> events(String body) {
		List<AMap<ACell,ACell>> events=new java.util.ArrayList<>();
		for (String line:body.split("\n")) {
			if (line.isBlank()||line.equals("event: message")) continue;
			assertTrue(line.startsWith("data:"),"unexpected line in SSE body: "+line);
			events.add(JSON.parse(line.substring(5).trim()));
		}
		return events;
	}

	private static HttpResponse<String> post(Javalin app,String json,String accept) throws IOException, InterruptedException {
		HttpRequest request=HttpRequest.newBuilder(URI.create("http://localhost:"+app.port()+"/mcp"))
			.header("Content-Type","application/json")
			.header("Accept",accept)
			.POST(HttpRequest.BodyPublishers.ofString(json))
			.build();
		try (HttpClient client=HttpClient.newHttpClient()) {
			return client.send(request,HttpResponse.BodyHandlers.ofString());
		}
	}
}
