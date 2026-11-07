package convex.api;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.SourceCodes;
import convex.core.data.Blobs;
import convex.core.data.prim.CVMLong;
import convex.core.message.AConnection;
import convex.core.message.LocalConnection;
import convex.core.message.Message;
import convex.core.message.MessageType;

public class RequestCorrelationTest {
	@Test public void testConfiguredTimeoutStillReturnsResult() throws Exception {
		try (ConvexRemote client=new ConvexRemote(null,null)) {
			client.setConnection(LocalConnection.createReturnable(m -> true,m -> true));
			client.setTimeout(1);
			Result result=client.requestStatus().get(5,TimeUnit.SECONDS);
			assertEquals(convex.core.ErrorCodes.TIMEOUT,result.getErrorCode());
			assertEquals(SourceCodes.COMM,result.getSource());
			assertFalse(client.hasPendingRequests());
		}
	}

	@Test public void testSynchronousWaitTimeoutsReleaseRequests() throws Exception {
		try (ConvexRemote client=new ConvexRemote(null,null)) {
			client.setConnection(LocalConnection.createReturnable(m -> true,m -> true));
			client.setTimeout(0);
			assertEquals(convex.core.ErrorCodes.TIMEOUT,client.requestStatusSync(0).getErrorCode());
			assertFalse(client.hasPendingRequests());
			assertThrows(java.util.concurrent.TimeoutException.class,() -> client.pingSync(0));
			assertFalse(client.hasPendingRequests());
			assertEquals(convex.core.ErrorCodes.TIMEOUT,client.querySync(CVMLong.ONE,null,0).getErrorCode());
			assertFalse(client.hasPendingRequests());
			assertThrows(convex.core.exceptions.ResultException.class,() -> client.getBalance(convex.core.cvm.Address.create(1)));
			assertFalse(client.hasPendingRequests());
		}
	}
	@Test public void testCancellingPingLeavesOtherRequestsIntact() throws Exception {
		try (ConvexRemote client=new ConvexRemote(null,null)) {
			ArrayList<Message> sent=new ArrayList<>();
			client.setConnection(LocalConnection.createReturnable(m -> {
				client.returnMessageHandler.accept(m);
				return true;
			},m -> { sent.add(m); return true; }));
			client.setTimeout(0);
			var ping=client.ping();
			var status=client.requestStatus();
			assertTrue(ping.cancel(false));
			assertFalse(status.isDone());
			assertTrue(client.hasPendingRequests());
			assertTrue(sent.get(1).returnResult(Result.value(CVMLong.ONE)));
			assertEquals(CVMLong.ONE,status.get(5,TimeUnit.SECONDS).getValue());
			assertFalse(client.hasPendingRequests());
		}
	}
	@Test public void testCancellationReachesTransportBeforeCallerCallback() throws Exception {
		assertPublicCompletionReleasesTransport(false);
	}

	@Test public void testAddedTimeoutReachesTransportBeforeCallerCallback() throws Exception {
		assertPublicCompletionReleasesTransport(true);
	}

	private void assertPublicCompletionReleasesTransport(boolean expire) throws Exception {
		try (ConvexRemote client=new ConvexRemote(null,null)) {
			LocalConnection connection=LocalConnection.createReturnable(m -> true,m -> true);
			client.setConnection(connection);
			client.setTimeout(0);
			var result=client.requestStatus();
			var entered=new java.util.concurrent.CountDownLatch(1);
			var release=new java.util.concurrent.CountDownLatch(1);
			result.whenComplete((r,e) -> {
				entered.countDown();
				try { release.await(); }
				catch (InterruptedException failure) { throw new AssertionError(failure); }
			});
			try (var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
				var cancellation=executor.submit(() -> expire
					? result.completeExceptionally(new java.util.concurrent.TimeoutException()) : result.cancel(false));
				try {
					assertTrue(entered.await(5,TimeUnit.SECONDS));
					assertEquals(!expire,result.isCancelled());
					assertTrue(result.isCompletedExceptionally());
					assertFalse(connection.hasPendingRequests(),"Public cancellation must release transport registration");
				} finally {
					release.countDown();
				}
				assertTrue(cancellation.get(5,TimeUnit.SECONDS));
			}
		}
	}
	@Test public void testLateReplyFromPreviousConnectionCannotCompleteNewRequest() throws Exception {
		try (ConvexRemote client = new ConvexRemote(null,null)) {
			LocalConnection oldConnection = LocalConnection.createReturnable(m -> true,m -> true);
			client.setConnection(oldConnection);
			var oldRequest = client.requestStatus();
			LocalConnection newConnection = LocalConnection.createReturnable(m -> true,m -> true);
			client.setConnection(newConnection);
			assertTrue(oldRequest.isDone());
			var newRequest = client.requestStatus();
			// Both physical connections start their ID allocation at zero. Deliver an
			// already-decoded old frame after reconnect, as an I/O callback could do.
			client.returnMessageHandler.accept(Message.createResult(Result.create(CVMLong.ZERO,CVMLong.ONE))
					.withConnection(oldConnection));
			assertFalse(newRequest.isDone(),"Old connection must not satisfy the new connection's request");
			client.returnMessageHandler.accept(Message.createResult(Result.create(CVMLong.ZERO,CVMLong.create(2)))
					.withConnection(newConnection));
			assertEquals(CVMLong.create(2),newRequest.get(1,TimeUnit.SECONDS).getValue());
		}
	}

	@Test public void testDuplicateIDDoesNotDiscardOriginalRequest() throws Exception {
		try (ConvexRemote client = new ConvexRemote(null,null)) {
			ArrayList<Message> sent = new ArrayList<>();
			client.setConnection(LocalConnection.createReturnable(m -> {
				client.returnMessageHandler.accept(m);
				return true;
			},m -> { sent.add(m); return true; }));
			Message request = Message.createStatusRequest(7);
			var original = client.message(request);
			assertFalse(original.isDone());
			Result duplicate = client.message(request).get(1,TimeUnit.SECONDS);
			assertTrue(duplicate.isError());
			assertEquals(SourceCodes.COMM,duplicate.getSource());
			assertEquals(1,sent.size(),"Duplicate request must not be sent");
			assertTrue(client.hasPendingRequests(),"Duplicate rejection must retain the original request");
			assertTrue(sent.getFirst().returnResult(Result.value(CVMLong.ONE)));
			assertEquals(CVMLong.ONE,original.get(1,TimeUnit.SECONDS).getValue());
			assertFalse(client.hasPendingRequests());

			// Reusing a completed request's ID remains supported.
			var reused = client.message(request);
			assertTrue(sent.getLast().returnResult(Result.value(CVMLong.create(2))));
			assertEquals(CVMLong.create(2),reused.get(1,TimeUnit.SECONDS).getValue());
		}
	}

	@Test public void testRawMessageAndSendFailureCompatibility() throws Exception {
		try (ConvexRemote client=new ConvexRemote(null,null)) {
			client.setConnection(LocalConnection.createReturnable(m -> {
				client.returnMessageHandler.accept(m);
				return true;
			},m -> m.returnResult(Result.value(CVMLong.ONE))));
			Result result=client.messageRaw(Message.createStatusRequest(900).getMessageData()).get(5,TimeUnit.SECONDS);
			assertEquals(CVMLong.create(900),result.getID());
			assertEquals(CVMLong.ONE,result.getValue());
			var invalid=client.request(Message.create(MessageType.DATA,Blobs.empty()));
			assertThrows(java.util.concurrent.ExecutionException.class,() -> invalid.get(5,TimeUnit.SECONDS));
			client.setConnection(LocalConnection.createReturnable(m -> true,m -> false));
			assertEquals(Result.FULL_CLIENT_BUFFER,client.requestStatus().get(5,TimeUnit.SECONDS));
			assertFalse(client.hasPendingRequests());
		}
	}

	/** A closed connection reports :CONNECT, for a request it leaves unanswered and for any later send. */
	@Test public void testClosedConnectionReportsConnect() throws Exception {
		try (ConvexRemote client=new ConvexRemote(null,null)) {
			LocalConnection connection=LocalConnection.createReturnable(m -> true,m -> true);
			client.setConnection(connection);
			client.setTimeout(0);
			var unanswered=client.requestStatus();
			assertFalse(unanswered.isDone());
			connection.close();
			assertEquals(ErrorCodes.CONNECT,unanswered.get(5,TimeUnit.SECONDS).getErrorCode());
			assertEquals(ErrorCodes.CONNECT,client.requestStatus().get(5,TimeUnit.SECONDS).getErrorCode());
			assertEquals(ErrorCodes.CONNECT,client.message(Message.createGoodBye()).get(5,TimeUnit.SECONDS).getErrorCode());
			assertFalse(client.hasPendingRequests());
		}
	}

	/** A failed write reports :IO, for a request and a message alike. */
	@Test public void testSendIOFailureReportsIO() throws Exception {
		try (ConvexRemote client=new ConvexRemote(null,null)) {
			client.setConnection(new FailingConnection(new IOException("Write failed")));
			assertEquals(ErrorCodes.IO,client.requestStatus().get(5,TimeUnit.SECONDS).getErrorCode());
			assertEquals(ErrorCodes.IO,client.message(Message.createGoodBye()).get(5,TimeUnit.SECONDS).getErrorCode());
			assertFalse(client.hasPendingRequests());
		}
	}

	/** An interrupted send reports :INTERRUPTED, and the calling thread keeps its interrupt status. */
	@Test public void testInterruptedSendKeepsInterruptStatus() throws Exception {
		try (ConvexRemote client=new ConvexRemote(null,null)) {
			client.setConnection(new FailingConnection(new InterruptedException()));
			try {
				assertEquals(ErrorCodes.INTERRUPTED,client.requestStatus().join().getErrorCode());
				assertTrue(Thread.interrupted());
				assertEquals(ErrorCodes.INTERRUPTED,client.message(Message.createGoodBye()).join().getErrorCode());
				assertTrue(Thread.interrupted());
			} finally {
				Thread.interrupted();
			}
			assertFalse(client.hasPendingRequests());
		}
	}

	/** A message too large to send fails the call, as an invalid request does, rather than giving a Result. */
	@Test public void testOversizedMessageFailsCall() throws Exception {
		try (ConvexRemote client=new ConvexRemote(null,null)) {
			client.setConnection(new FailingConnection(new IllegalArgumentException("Too large for one frame")));
			ExecutionException failed=assertThrows(ExecutionException.class,
				() -> client.requestStatus().get(5,TimeUnit.SECONDS));
			assertInstanceOf(IllegalArgumentException.class,failed.getCause());
			failed=assertThrows(ExecutionException.class,
				() -> client.message(Message.createGoodBye()).get(5,TimeUnit.SECONDS));
			assertInstanceOf(IllegalArgumentException.class,failed.getCause());
			assertFalse(client.hasPendingRequests());
		}
	}

	/** An open connection whose every send fails with the given exception. */
	private static final class FailingConnection extends AConnection {
		private final Exception failure;

		FailingConnection(Exception failure) {
			this.failure=failure;
		}

		@Override public boolean sendMessage(Message msg) throws IOException, InterruptedException {
			if (failure instanceof IOException e) throw e;
			if (failure instanceof InterruptedException e) throw e;
			throw (RuntimeException)failure;
		}

		@Override public boolean trySendMessage(Message msg) { return false; }
		@Override public InetSocketAddress getRemoteAddress() { return null; }
		@Override public boolean isClosed() { return false; }
		@Override public void close() { closeRequests(); }
		@Override public long getReceivedCount() { return 0; }
	}
}
