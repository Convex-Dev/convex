package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.api.ContentTypes;
import convex.api.ConvexRemote;
import convex.core.Result;
import convex.core.cvm.CVMEncoder;
import convex.core.data.Blob;
import convex.core.data.prim.CVMLong;
import convex.core.message.LocalConnection;
import convex.core.message.Message;
import convex.restapi.api.ChainAPI;
import io.javalin.Javalin;

public class MessageCorrelationTest extends ARESTTest {
	/** Hold replies until both HTTP calls are registered, then reply in reverse order. */
	@Test public void testOverlappingHTTPRequestsWithSameID() throws Exception {
		try (HeldClient client = new HeldClient()) {
			ChainAPI api = new ChainAPI(server);
			api.convex = client;
			Javalin app = Javalin.create(config -> config.routes.post("/message",api::handleMessage));
			try {
				app.start(0);
				URI uri = URI.create("http://localhost:"+app.port()+"/message");
				// Also overlap a request made internally on the shared client connection.
				var internal = client.requestStatus();
				Message internalMessage = client.takeRequest();
				long callerID = ((CVMLong) internalMessage.getRequestID()).longValue();
				var first = postAsync(uri,Message.createStatusRequest(callerID));
				Message firstMessage = client.takeRequest();
				var second = postAsync(uri,Message.createStatusRequest(callerID));
				Message secondMessage = client.takeRequest();
				assertNotEquals(internalMessage.getRequestID(),firstMessage.getRequestID());
				assertNotEquals(firstMessage.getRequestID(),secondMessage.getRequestID());
				assertTrue(secondMessage.returnResult(Result.value(CVMLong.create(22))));
				assertResponse(second,callerID,22);
				assertTrue(firstMessage.returnResult(Result.value(CVMLong.create(11))));
				assertResponse(first,callerID,11);
				assertTrue(internalMessage.returnResult(Result.value(CVMLong.create(33))));
				assertEquals(CVMLong.create(33),internal.get(5,TimeUnit.SECONDS).getValue());
				assertFalse(client.hasPendingRequests());
			} finally {
				// Resolve pending requests before stopping the HTTP server on assertion failure.
				client.close();
				app.stop();
			}
		}
	}

	private static CompletableFuture<HttpResponse<byte[]>> postAsync(URI uri, Message message) {
		HttpRequest request = HttpRequest.newBuilder(uri)
				.header("Content-Type",ContentTypes.CVX_RAW)
				.header("Accept",ContentTypes.CVX_RAW)
				.POST(HttpRequest.BodyPublishers.ofByteArray(message.getMessageData().getBytes()))
				.build();
		return httpClient.sendAsync(request,HttpResponse.BodyHandlers.ofByteArray());
	}

	private static void assertResponse(CompletableFuture<HttpResponse<byte[]>> future, long id, long value) throws Exception {
		HttpResponse<byte[]> response = future.get(5,TimeUnit.SECONDS);
		assertEquals(200,response.statusCode());
		Result result = (Result) CVMEncoder.INSTANCE.decodeMultiCell(Blob.wrap(response.body()));
		assertFalse(result.isError(),result.toString());
		assertEquals(CVMLong.create(id),result.getID());
		assertEquals(CVMLong.create(value),result.getValue());
	}

	private static class HeldClient extends ConvexRemote {
		private final ArrayBlockingQueue<Message> requests = new ArrayBlockingQueue<>(3);

		HeldClient() {
			super(null,null);
			setConnection(LocalConnection.createReturnable(message -> {
				returnMessageHandler.accept(message);
				return true;
			},requests::offer));
		}

		Message takeRequest() throws InterruptedException {
			Message message = requests.poll(5,TimeUnit.SECONDS);
			assertNotNull(message,"Each HTTP request must reach the peer despite overlapping caller IDs");
			return message;
		}
	}
}
