package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
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
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.prim.CVMLong;
import convex.core.util.JSON;
import convex.java.ConvexHTTP;

class QueryWatchAPITest extends ARESTTest {

	private static final Duration TIMEOUT=Duration.ofSeconds(10);

	@Test
	void requiresEventStreamAndOneQuerySource() throws Exception {
		HttpResponse<String> noAccept=get(API_PATH+"/watch?source=42");
		assertEquals(406,noAccept.statusCode());

		HttpRequest noSource=HttpRequest.newBuilder()
			.uri(URI.create(API_PATH+"/watch"))
			.header("Accept","text/event-stream")
			.GET().build();
		HttpResponse<String> response=httpClient.send(noSource,HttpResponse.BodyHandlers.ofString());
		assertEquals(400,response.statusCode());
	}

	@Test
	void streamsInitialAndChangedQueryResultsAsJSON() throws Exception {
		String query="?source="+encode("*timestamp*")+"&format=json";
		HttpRequest request=HttpRequest.newBuilder()
			.uri(URI.create(API_PATH+"/watch"+query))
			.header("Accept","text/event-stream")
			.GET().build();

		HttpResponse<InputStream> response=httpClient.send(request,HttpResponse.BodyHandlers.ofInputStream());
		assertEquals(200,response.statusCode());
		try (InputStream input=response.body(); ConvexHTTP convex=connect()) {
			SseReader reader=new SseReader(input);
			SseEvent initial=readAsync(reader);
			assertEquals("result",initial.type());
			AMap<ACell,ACell> initialEnvelope=JSON.parse(initial.data());
			AMap<ACell,ACell> initialResult=initialEnvelope.getIn("result");
			CVMLong initialTimestamp=initialResult.getIn("value");

			Result transaction=convex.transactSync("(+ 1 2)");
			assertTrue(!transaction.isError(),()->"Transaction failed: "+transaction);

			SseEvent changed=readAsync(reader);
			assertEquals("result",changed.type());
			assertTrue(Long.parseLong(changed.id())>Long.parseLong(initial.id()));
			AMap<ACell,ACell> changedEnvelope=JSON.parse(changed.data());
			AMap<ACell,ACell> changedResult=changedEnvelope.getIn("result");
			CVMLong changedTimestamp=changedResult.getIn("value");
			assertTrue(changedTimestamp.longValue()>initialTimestamp.longValue());
		}
	}

	private static SseEvent readAsync(SseReader reader) throws Exception {
		return CompletableFuture.supplyAsync(reader::readEvent)
			.get(TIMEOUT.toMillis(),TimeUnit.MILLISECONDS);
	}

	private static String encode(String value) {
		return URLEncoder.encode(value,StandardCharsets.UTF_8);
	}

	private static final class SseReader {
		private final BufferedReader reader;

		private SseReader(InputStream input) {
			reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8));
		}

		private SseEvent readEvent() {
			try {
				String id=null;
				String type=null;
				String data=null;
				for (String line;(line=reader.readLine())!=null;) {
					if (line.isEmpty()) {
						if (type!=null) return new SseEvent(id,type,data);
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
				throw new AssertionError("Query watch stream ended before an event arrived");
			} catch (IOException e) {
				throw new AssertionError("Unable to read query watch event",e);
			}
		}
	}

	private record SseEvent(String id, String type, String data) {}
}
