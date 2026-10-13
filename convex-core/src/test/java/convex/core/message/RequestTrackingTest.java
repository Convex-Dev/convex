package convex.core.message;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import convex.core.Result;
import convex.core.data.prim.CVMLong;

class RequestTrackingTest {
	@Test void configuredTimeoutCleansUpAndDoesNotOverwriteAReply() throws Exception {
		LocalConnection connection=LocalConnection.createPair(m -> true,m -> true);
		try {
			var expired=connection.request(Message.createStatusRequest(null),1);
			var error=assertThrows(java.util.concurrent.ExecutionException.class,() -> expired.get(5,TimeUnit.SECONDS));
			assertInstanceOf(TimeoutException.class,error.getCause());
			assertFalse(connection.hasPendingRequests());
			assertFalse(connection.completeRequest(Message.createResult(Result.value(CVMLong.ONE).withID(CVMLong.ZERO)).withConnection(connection)));

			var signal=new CompletableFuture<Message>();
			var won=connection.request(Message.createStatusRequest(null),0,signal);
			Message reply=Message.createResult(Result.value(CVMLong.ONE).withID(CVMLong.ONE)).withConnection(connection);
			assertTrue(connection.completeRequest(reply));
			assertFalse(signal.completeExceptionally(new TimeoutException()));
			assertSame(reply,won.get(5,TimeUnit.SECONDS));
			assertFalse(connection.hasPendingRequests());
		} finally {
			connection.close();
		}
	}

	@Test void timeoutAddedToReturnedFutureAlsoReleasesRequest() throws Exception {
		LocalConnection connection=LocalConnection.createPair(m -> true,m -> true);
		var source=new CompletableFuture<Message>();
		var reply=connection.request(Message.createStatusRequest(null),0,source);
		CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
		reply.whenComplete((value,error) -> {
			entered.countDown();
			try { release.await(); }
			catch (InterruptedException e) { throw new AssertionError(e); }
		});
		try {
			reply.orTimeout(0,TimeUnit.MILLISECONDS);
			assertTrue(entered.await(5,TimeUnit.SECONDS));
			assertTrue(source.isCancelled());
			assertFalse(connection.hasPendingRequests());
			assertInstanceOf(TimeoutException.class,assertThrows(java.util.concurrent.ExecutionException.class,
				() -> reply.get(5,TimeUnit.SECONDS)).getCause());
		} finally {
			release.countDown();
			connection.close();
		}
	}

	@Test void timedGetOnlyStopsWaitingUntilExplicitCancellation() throws Exception {
		LocalConnection connection=LocalConnection.createPair(m -> true,m -> true);
		try {
			var reply=connection.request(Message.createStatusRequest(null),0);
			assertThrows(TimeoutException.class,() -> reply.get(0,TimeUnit.MILLISECONDS));
			assertFalse(reply.isDone());
			assertTrue(connection.hasPendingRequests());
			assertTrue(reply.cancel(false));
			assertFalse(connection.hasPendingRequests());
		} finally {
			connection.close();
		}
	}

	@Test void cleanupPrecedesBlockedTimeoutCallback() throws Exception {
		assertCleanupBeforeCallback(false,false);
		assertCleanupBeforeCallback(false,true);
	}

	@Test void cleanupPrecedesBlockedCancellationCallback() throws Exception {
		assertCleanupBeforeCallback(true,false);
		assertCleanupBeforeCallback(true,true);
	}

	private void assertCleanupBeforeCallback(boolean cancel,boolean controlled) throws Exception {
		LocalConnection connection=LocalConnection.createPair(m -> true,m -> true);
		var source=new CompletableFuture<Message>();
		var reply=controlled ? connection.request(Message.createStatusRequest(null),0,source)
			: connection.request(Message.createStatusRequest(null),0);
		CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
		reply.whenComplete((value,error) -> {
			entered.countDown();
			try { release.await(); }
			catch (InterruptedException e) { throw new AssertionError(e); }
		});
		try (var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
			var completion=executor.submit(() -> {
				if (cancel) reply.cancel(false);
				else (controlled ? source : reply).completeExceptionally(new TimeoutException());
			});
			try {
				assertTrue(entered.await(5,TimeUnit.SECONDS));
				assertFalse(connection.hasPendingRequests(),"Cleanup must precede caller callbacks");
			} finally {
				release.countDown();
				connection.close();
			}
			completion.get(5,TimeUnit.SECONDS);
		}
	}

	@Test void completingOldFutureCannotRemoveReusedID() throws Exception {
		LocalConnection connection=LocalConnection.createPair(m -> true,m -> true);
		try {
			Message request=Message.createStatusRequest(7);
			var first=connection.sendRequest(request,0);
			Message reply=Message.createResult(Result.create(CVMLong.create(7),CVMLong.ONE)).withConnection(connection);
			assertTrue(connection.completeRequest(reply));
			var second=connection.sendRequest(request,0);
			assertFalse(first.cancel(false));
			assertFalse(first.completeExceptionally(new TimeoutException()));
			assertFalse(first.complete(reply));
			assertFalse(second.isDone());
			assertTrue(connection.hasPendingRequests());
			assertTrue(connection.completeRequest(reply));
			assertSame(reply,second.get(5,TimeUnit.SECONDS));
			assertFalse(connection.hasPendingRequests());
		} finally {
			connection.close();
		}
	}

	private static boolean receive(Message message) {
		try {
			AConnection connection=message.getConnection();
			if (connection.completeRequest(message)) return true;
			return message.returnResult(Result.value(CVMLong.ONE));
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	@Test void synchronousRepliesInBothDirections() throws Exception {
		LocalConnection connection=LocalConnection.createPair(RequestTrackingTest::receive,RequestTrackingTest::receive);
		try {
			for (LocalConnection end:new LocalConnection[] {connection,connection.getPaired()}) {
				Message reply=end.request(Message.createStatusRequest(null),0).get(5,TimeUnit.SECONDS);
				assertEquals(CVMLong.ZERO,reply.getResultID());
				assertEquals(CVMLong.ONE,reply.toResult().getValue());
				assertSame(end,reply.getConnection());
				assertFalse(end.hasPendingRequests());
			}
		} finally {
			connection.close();
		}
	}

	@Test void outOfOrderRepliesAndRawIDs() throws Exception {
		ArrayList<Message> received=new ArrayList<>();
		LocalConnection connection=LocalConnection.createPair(RequestTrackingTest::receive,m -> { received.add(m); return true; });
		try {
			Message raw=Message.createStatusRequest(700);
			var first=connection.sendRequest(raw,0);
			var second=connection.request(Message.createStatusRequest(null),0);
			assertTrue(received.get(1).returnResult(Result.value(CVMLong.ONE)));
			assertTrue(second.isDone());
			assertFalse(first.isDone());
			assertTrue(received.get(0).returnResult(Result.value(CVMLong.ZERO)));
			assertEquals(raw.getRequestID(),first.get(5,TimeUnit.SECONDS).getResultID());
			assertFalse(connection.hasPendingRequests());
		} finally {
			connection.close();
		}
	}

	@Test void timeoutAndCancellationDiscardLateReplies() throws Exception {
		LocalConnection connection=LocalConnection.createPair(m -> true,m -> true);
		try {
			var timedOut=new CompletableFuture<Message>();
			connection.request(Message.createStatusRequest(null),0,timedOut);
			// Drive expiry explicitly: no dependence on the scheduler or elapsed time.
			timedOut.completeExceptionally(new TimeoutException());
			assertFalse(connection.hasPendingRequests());
			assertFalse(connection.completeRequest(Message.createResult(Result.value(CVMLong.ONE).withID(CVMLong.ZERO)).withConnection(connection)));
			var cancelled=connection.request(Message.createStatusRequest(null),0);
			assertTrue(cancelled.cancel(false));
			assertFalse(connection.hasPendingRequests());
		} finally {
			connection.close();
		}
	}

	@Test void sendFailureAndPreCancelledRequestLeaveNoWaiter() {
		LocalConnection connection=LocalConnection.createPair(m -> true,m -> false);
		try {
			var failed=connection.request(Message.createStatusRequest(null),0);
			assertInstanceOf(RejectedExecutionException.class,assertThrows(java.util.concurrent.CompletionException.class,failed::join).getCause());
			assertFalse(connection.hasPendingRequests());
			var cancelled=new CompletableFuture<Message>();
			cancelled.cancel(false);
			assertSame(cancelled,connection.request(Message.createStatusRequest(null),0,cancelled));
			assertFalse(connection.hasPendingRequests());
		} finally {
			connection.close();
		}
	}

	@Test void closingEitherEndCompletesBothDirections() {
		LocalConnection connection=LocalConnection.createPair(m -> true,m -> true);
		LocalConnection paired=connection.getPaired();
		var first=connection.request(Message.createStatusRequest(null),0);
		var second=paired.request(Message.createStatusRequest(null),0);
		paired.close();
		assertTrue(first.isCompletedExceptionally());
		assertTrue(second.isCompletedExceptionally());
		assertFalse(connection.hasPendingRequests());
		assertFalse(paired.hasPendingRequests());
		assertTrue(connection.request(Message.createStatusRequest(null),0).isCompletedExceptionally());
	}

	@Test void closeBeforeRegistrationCannotOrphanFuture() throws Exception {
		CountDownLatch checked=new CountDownLatch(1);
		CountDownLatch release=new CountDownLatch(1);
		AConnection connection=new AConnection() {
			@Override public boolean isClosed() {
				checked.countDown();
				try { assertTrue(release.await(5,TimeUnit.SECONDS)); }
				catch (InterruptedException e) { throw new AssertionError(e); }
				return false; // State observed before the concurrent close.
			}
			@Override public boolean sendMessage(Message message) { fail("Closed request must not be sent"); return false; }
			@Override public boolean trySendMessage(Message message) { return sendMessage(message); }
			@Override public InetSocketAddress getRemoteAddress() { return null; }
			@Override public long getReceivedCount() { return 0; }
			@Override public void close() { closeRequests(); }
		};
		try (var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
			var submitted=executor.submit(() -> connection.request(Message.createStatusRequest(null),0));
			try {
				assertTrue(checked.await(5,TimeUnit.SECONDS));
				connection.close();
			} finally {
				release.countDown();
			}
			assertTrue(submitted.get(5,TimeUnit.SECONDS).isCompletedExceptionally());
			assertFalse(connection.hasPendingRequests());
		}
	}
}
