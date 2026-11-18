package convex.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.Result;

/**
 * A closed HTTP client ends its own requests and keeps nothing of them: a request is
 * tracked only until its exchange completes, closing cancels the exchanges still
 * waiting, and a request made after close is never sent.
 */
public class ConvexHTTPCloseTest {

	/** A server for one connection that reads its request, and answers it if given an answer. */
	private static final class OneConnectionServer implements AutoCloseable {
		final ServerSocket socket=new ServerSocket(0);
		final CountDownLatch received=new CountDownLatch(1);
		/** Completes once the client has closed the connection */
		final CompletableFuture<Void> clientClosed=new CompletableFuture<>();
		private final String answer;
		private volatile Socket accepted;
		private final Thread serving;

		OneConnectionServer(String answer) throws IOException {
			this.answer=answer;
			serving=Thread.ofVirtual().start(this::serve);
		}

		private void serve() {
			try (Socket s=socket.accept()) {
				accepted=s;
				// Accepted as close ran: close may have missed this socket, so it closes here
				if (socket.isClosed()) return;
				InputStream in=s.getInputStream();
				StringBuilder request=new StringBuilder();
				byte[] buffer=new byte[4096];
				int n;
				while ((n=in.read(buffer))>=0) {
					request.append(new String(buffer,0,n,StandardCharsets.ISO_8859_1));
					if (request.indexOf("\r\n\r\n")>=0 && received.getCount()>0) {
						received.countDown();
						if (answer!=null) {
							OutputStream out=s.getOutputStream();
							out.write(answer.getBytes(StandardCharsets.ISO_8859_1));
							out.flush();
						}
					}
				}
				clientClosed.complete(null);
			} catch (IOException e) {
				// A reset is the client closing the connection too
				clientClosed.complete(null);
			}
		}

		URI uri() {
			return URI.create("http://localhost:"+socket.getLocalPort());
		}

		@Override
		public void close() throws IOException, InterruptedException {
			try {
				// The listener closes first, so a connection accepted from now on is closed by serve
				socket.close();
			} finally {
				try {
					Socket s=accepted;
					if (s!=null) s.close();
				} finally {
					// Closing the sockets ends the serving thread
					serving.join();
				}
			}
		}
	}

	@Test
	public void closingEndsAWaitingRequestAndItsConnection() throws Exception {
		// The client closes again on the way out, which does nothing
		try (OneConnectionServer server=new OneConnectionServer(null);
				ConvexHTTP client=ConvexHTTP.connect(server.uri())) {
			CompletableFuture<Result> status=client.requestStatus();
			assertTrue(server.received.await(5,TimeUnit.SECONDS),"the server has the request");
			assertEquals(1,client.getPendingExchangeCount());

			client.close();
			assertSame(Result.CLOSED_CONNECTION,status.get(5,TimeUnit.SECONDS));
			assertEquals(0,client.getPendingExchangeCount());
			// The exchange itself ended, not only its future
			server.clientClosed.get(5,TimeUnit.SECONDS);
			assertFalse(client.isConnected());
		}
	}

	@Test
	public void aRequestAfterCloseIsNeverSent() throws Exception {
		// The client closes again on the way out, which does nothing
		try (OneConnectionServer server=new OneConnectionServer(null);
				ConvexHTTP client=ConvexHTTP.connect(server.uri())) {
			client.close();
			CompletableFuture<Result> status=client.requestStatus();
			assertTrue(status.isDone(),"answered without a round trip");
			assertSame(Result.CLOSED_CONNECTION,status.get());
			assertEquals(0,client.getPendingExchangeCount());
			assertEquals(1,server.received.getCount(),"nothing reached the server");
		}
	}

	@Test
	public void anAnsweredRequestIsNoLongerTracked() throws Exception {
		String answer="HTTP/1.1 200 OK\r\nContent-Type: application/cvx\r\nContent-Length: 2\r\n\r\n{}";
		try (OneConnectionServer server=new OneConnectionServer(answer);
				ConvexHTTP client=ConvexHTTP.connect(server.uri())) {
			Result status=client.requestStatus().get(5,TimeUnit.SECONDS);
			assertFalse(status.isError(),status.toString());
			assertEquals(0,client.getPendingExchangeCount());
		}
	}

	@Test
	public void aFailedRequestIsNoLongerTracked() throws Exception {
		int port;
		// A port nothing listens on: the connection is refused
		try (ServerSocket unused=new ServerSocket(0)) {
			port=unused.getLocalPort();
		}
		try (ConvexHTTP client=ConvexHTTP.connect(URI.create("http://localhost:"+port))) {
			Result status=client.requestStatus().get(5,TimeUnit.SECONDS);
			assertTrue(status.isError(),status.toString());
			assertEquals(0,client.getPendingExchangeCount());
		}
	}
}
