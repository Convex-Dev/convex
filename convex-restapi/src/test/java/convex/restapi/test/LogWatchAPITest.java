package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.Result;
import convex.core.cvm.Address;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.util.JSON;
import convex.java.ConvexHTTP;

class LogWatchAPITest extends ARESTTest {

	private static final Duration TIMEOUT=Duration.ofSeconds(10);

	@Test
	void requiresEventStreamAndAddressFilter() throws Exception {
		HttpResponse<String> noAccept=get(API_PATH+"/watch/logs?address=0");
		assertEquals(406,noAccept.statusCode());

		HttpRequest noAddress=HttpRequest.newBuilder()
			.uri(URI.create(API_PATH+"/watch/logs"))
			.header("Accept","text/event-stream")
			.GET().build();
		HttpResponse<String> response=httpClient.send(noAddress,HttpResponse.BodyHandlers.ofString());
		assertEquals(400,response.statusCode());
	}

	@Test
	void streamsAddressEventAndScopeMatchAsJSON() throws Exception {
		try (ConvexHTTP convex=newClient()) {
			Result deployed=convex.transactSync(
				"(deploy '(defn emit ^:callable [] (log :LOG-WATCH-TEST 100)))");
			Address actor=deployed.getValue();
			assertNotNull(actor);

			String query="?address="+actor.longValue()
				+"&event="+encode(":LOG-WATCH-TEST")
				+"&scope="+encode(":USD")
				+"&format=json";
			HttpRequest request=HttpRequest.newBuilder()
				.uri(URI.create(API_PATH+"/watch/logs"+query))
				.header("Accept","text/event-stream")
				.GET().build();

			HttpResponse<InputStream> response=httpClient.send(request,HttpResponse.BodyHandlers.ofInputStream());
			assertEquals(200,response.statusCode());
			try (InputStream input=response.body()) {
				CompletableFuture<SseEvent> received=CompletableFuture.supplyAsync(()->readLogEvent(input));
				Result result=convex.transactSync("(call ["+actor+" :USD] (emit))");
				assertSucceeded(result);

				SseEvent event=received.get(TIMEOUT.toMillis(),TimeUnit.MILLISECONDS);
				assertEquals("log",event.type());
				assertTrue(event.id().matches("\\d+:\\d+:\\d+"),event.id());
				AMap<ACell,ACell> envelope=JSON.parse(event.data());
				AVector<ACell> entry=envelope.getIn("entry");
				assertEquals(CVMLong.create(actor.longValue()),entry.get(0));
				assertEquals(Strings.create("USD"),entry.get(1));
				@SuppressWarnings("unchecked")
				AVector<ACell> values=(AVector<ACell>)entry.get(3);
				assertEquals(Strings.create("LOG-WATCH-TEST"),values.get(0));
			}
		}
	}

	private static String encode(String value) {
		return URLEncoder.encode(value,StandardCharsets.UTF_8);
	}

	private static SseEvent readLogEvent(InputStream input) {
		try {
			BufferedReader reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8));
			String id=null;
			String type=null;
			String data=null;
			for (String line;(line=reader.readLine())!=null;) {
				if (line.isEmpty()) {
					if ("log".equals(type)) return new SseEvent(id,type,data);
					id=null;
					type=null;
					data=null;
				} else if (line.startsWith("id: ")) {
					id=line.substring(4);
				} else if (line.startsWith("event: ")) {
					type=line.substring(7);
				} else if (line.startsWith("data: ")) {
					data=line.substring(6);
				}
			}
			throw new AssertionError("Log watch stream ended before an event arrived");
		} catch (Exception e) {
			throw new AssertionError("Unable to read log watch event",e);
		}
	}

	private record SseEvent(String id, String type, String data) {}
}
