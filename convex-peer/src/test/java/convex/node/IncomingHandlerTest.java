package convex.node;

import static org.junit.jupiter.api.Assertions.*;

import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import convex.api.ConvexRemote;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.message.AConnection;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.message.MessageType;
import convex.core.store.MemoryStore;
import convex.lattice.generic.MaxLattice;
import convex.net.IncomingHandler;

class IncomingHandlerTest {

	private static Message decode(Message message) {
		try { message.getPayload(null); return message; }
		catch (Exception e) { throw new AssertionError(e); }
	}

	private static void reply(Message message,long value) {
		assertTrue(decode(message).returnResult(Result.create(null,CVMLong.create(value))));
	}

	@Test void customHandlerNeedsNoNodeOrPropagatorAndIsCreatedBeforeTraffic() throws Exception {
		var opened=new CompletableFuture<AConnection>();
		var closed=new CompletableFuture<Void>();
		var factories=new AtomicInteger();
		var messages=new AtomicInteger();
		var closes=new AtomicInteger();
		try (var listener=new LatticeListener(NodeConfig.port(0))) {
			listener.setConnectionHandler(connection -> {
				factories.incrementAndGet();
				opened.complete(connection);
				return new IncomingHandler() {
					@Override public Predicate<Message> handle(Message message) {
						assertSame(connection,message.getConnection());
						messages.incrementAndGet();
						reply(message,42);
						return null;
					}
					@Override public void close() {
						closes.incrementAndGet();
						closed.complete(null);
					}
				};
			});
			listener.launch();
			try (var client=ConvexRemote.connect(listener.getHostAddress())) {
				assertNotNull(opened.get(5,TimeUnit.SECONDS));
				assertEquals(0,messages.get());
				// A new factory must not replace an established connection's handler.
				listener.setConnectionHandler(connection -> { fail("Existing connection was reassigned"); return null; });
				for (int i=0;i<200;i++) {
					assertEquals(CVMLong.create(42),client.request(Message.createPing(1)).get(5,TimeUnit.SECONDS).getValue());
				}
				assertEquals(1,factories.get());
				assertEquals(200,messages.get());
			}
			closed.get(5,TimeUnit.SECONDS);
			listener.close();
			assertEquals(1,closes.get());
		}
	}

	@Test void nullAndThrowingFactoriesRefuseSocketsButListenerSurvives() throws Exception {
		try (var listener=new LatticeListener(NodeConfig.port(0))) {
			listener.setConnectionHandler(connection -> null);
			listener.launch();
			assertClosedOnAccept(listener);
			listener.setConnectionHandler(connection -> { throw new IllegalStateException("factory failure"); });
			assertClosedOnAccept(listener);
			listener.setConnectionHandler(connection -> message -> {
				reply(message,1);
				return null;
			});
			try (var client=ConvexRemote.connect(listener.getHostAddress())) {
				assertEquals(CVMLong.ONE,client.request(Message.createPing(1)).get(5,TimeUnit.SECONDS).getValue());
			}
		}
	}

	private static void assertClosedOnAccept(LatticeListener listener) throws Exception {
		try (var socket=new Socket()) {
			socket.connect(listener.getHostAddress(),5000);
			socket.setSoTimeout(5000);
			assertEquals(-1,socket.getInputStream().read());
		}
	}

	@Test void handlerFailureClosesOnlyItsConnectionAndRunsCleanup() throws Exception {
		var closed=new CompletableFuture<Void>();
		try (var listener=new LatticeListener(NodeConfig.port(0))) {
			listener.setConnectionHandler(connection -> new IncomingHandler() {
				@Override public Predicate<Message> handle(Message message) { throw new IllegalStateException("handler failure"); }
				@Override public void close() { closed.complete(null); }
			});
			listener.launch();
			try (var client=ConvexRemote.connect(listener.getHostAddress())) {
				assertTrue(client.request(Message.createPing(1)).get(5,TimeUnit.SECONDS).isError());
				closed.get(5,TimeUnit.SECONDS);
			}
			assertTrue(listener.isRunning());
		}
	}

	@Test void wrapperCanRejectAnUpdateAndDelegateQueriesAndLaterUpdates() throws Exception {
		try (var store=new MemoryStore();
				var node=new NodeServer<>(RelayHost.open(MaxLattice.create(),store));
				var listener=new LatticeListener(NodeConfig.port(0))) {
			var group=new LatticePropagator(store);
			node.addPropagator(group);
			var writes=new AtomicInteger();
			var closed=new CompletableFuture<Void>();
			listener.registerPropagator(group);
			listener.setConnectionHandler(connection -> {
				var standard=group.accept(connection);
				return new IncomingHandler() {
					@Override public Predicate<Message> handle(Message message) {
						if (decode(message).getType()==MessageType.LATTICE_VALUE && writes.getAndIncrement()==0) {
							message.returnResult(Result.error(ErrorCodes.TRUST,"Writes not admitted"));
							return null;
						}
						return standard.handle(message);
					}
					@Override public void close() { standard.close(); closed.complete(null); }
				};
			});
			node.launch();
			listener.launch();
			try (var client=ConvexRemote.connect(listener.getHostAddress())) {
				assertEquals(ErrorCodes.TRUST,client.request(update(123)).get(5,TimeUnit.SECONDS).getErrorCode());
				assertEquals(CVMLong.ZERO,node.getLocalValue());
				assertEquals(0,group.getInboundStats().messagesReceived(),"Denied write never entered the protocol endpoint");
				assertFalse(client.request(update(456)).get(5,TimeUnit.SECONDS).isError());
				assertEquals(CVMLong.create(456),node.getLocalValue());
				group.whenAnnounced(value -> CVMLong.create(456).equals(value)).get(5,TimeUnit.SECONDS);
				var query=Message.create(MessageType.LATTICE_QUERY,Vectors.create(MessageTag.LATTICE_QUERY,CVMLong.ONE,Vectors.empty()));
				assertEquals(CVMLong.create(456),client.request(query).get(5,TimeUnit.SECONDS).getValue());
				assertTrue(listener.unregisterPropagator(group),"Revocation also finds a wrapped propagator adapter");
				closed.get(5,TimeUnit.SECONDS);
				assertEquals(0,group.getInboundStats().connections());
			}
		}
	}

	private static Message update(long value) {
		return Message.create(MessageType.LATTICE_VALUE,Vectors.create(MessageTag.LATTICE_VALUE,CVMLong.ONE,Vectors.empty(),CVMLong.create(value)));
	}

	@Test void repliesCompleteServerRequestsWithoutReenteringTheApplication() throws Exception {
		var handled=new AtomicInteger();
		try (var listener=new LatticeListener(NodeConfig.port(0))) {
			listener.setConnectionHandler(connection -> message -> {
				handled.incrementAndGet();
				connection.request(Message.createPing(null),5000).thenAccept(reply -> {
					reply(message,91);
				});
				return null;
			});
			listener.launch();
			try (var client=ConvexRemote.connect(listener.getHostAddress())) {
				client.setUnsolicitedMessageHandler(message -> reply(message,1));
				assertEquals(CVMLong.create(91),client.request(Message.createPing(1)).get(5,TimeUnit.SECONDS).getValue());
				assertEquals(1,handled.get(),"The correlated reply bypasses the application handler");
			}
		}
	}

	@Test void closingListenerClosesIdleHandlersExactlyOnce() throws Exception {
		var opened=new CompletableFuture<Void>();
		var closes=new AtomicInteger();
		try (var listener=new LatticeListener(NodeConfig.port(0))) {
			listener.setConnectionHandler(connection -> {
				opened.complete(null);
				return new IncomingHandler() {
					@Override public Predicate<Message> handle(Message message) { fail("No message sent"); return null; }
					@Override public void close() { closes.incrementAndGet(); }
				};
			});
			listener.launch();
			try (var socket=new Socket()) {
				socket.connect(listener.getHostAddress(),5000);
				opened.get(5,TimeUnit.SECONDS);
				listener.close();
				listener.close();
				assertEquals(1,closes.get());
			}
		}
	}

	@Test void interceptorCanRejectWithoutDecodingThePayload() throws Exception {
		var rejected=new CompletableFuture<Void>();
		try (var listener=new LatticeListener(NodeConfig.port(0))) {
			listener.setConnectionHandler(connection -> message -> {
				assertSame(connection,message.getConnection());
				message.closeConnection();
				rejected.complete(null);
				return null;
			});
			listener.launch();
			try (var socket=new Socket()) {
				socket.connect(listener.getHostAddress(),5000);
				socket.setSoTimeout(5000);
				socket.getOutputStream().write(new byte[] {1,(byte)0xff});
				socket.getOutputStream().flush();
				rejected.get(5,TimeUnit.SECONDS);
				assertEquals(-1,socket.getInputStream().read());
			}
		}
	}

	@Test void disconnectCallbackMayCloseTheListenerWithoutWaitingOnItself() throws Exception {
		var opened=new CompletableFuture<Void>();
		var closed=new CompletableFuture<Void>();
		try (var listener=new LatticeListener(NodeConfig.port(0))) {
			listener.setConnectionHandler(connection -> {
				opened.complete(null);
				return new IncomingHandler() {
					@Override public Predicate<Message> handle(Message message) { return null; }
					@Override public void close() {
						listener.close();
						closed.complete(null);
					}
				};
			});
			listener.launch();
			try (var socket=new Socket()) {
				socket.connect(listener.getHostAddress(),5000);
				opened.get(5,TimeUnit.SECONDS);
			}
			closed.get(5,TimeUnit.SECONDS);
			assertFalse(listener.isRunning());
		}
	}

	@Test void retryFailureClosesAndCleansUpTheConnection() throws Exception {
		var closed=new CompletableFuture<Void>();
		try (var listener=new LatticeListener(NodeConfig.port(0))) {
			listener.setConnectionHandler(connection -> new IncomingHandler() {
				@Override public Predicate<Message> handle(Message message) {
					return retry -> { throw new IllegalStateException("retry failure"); };
				}
				@Override public void close() { closed.complete(null); }
			});
			listener.launch();
			try (var client=ConvexRemote.connect(listener.getHostAddress())) {
				assertTrue(client.request(Message.createPing(1)).get(5,TimeUnit.SECONDS).isError());
				closed.get(5,TimeUnit.SECONDS);
			}
		}
	}

	@Test void backpressureRetriesOnceOffTheEventLoopAndPreservesOrder() throws Exception {
		var entered=new CountDownLatch(1);
		var release=new CountDownLatch(1);
		var calls=new AtomicInteger();
		var retries=new AtomicInteger();
		try (var listener=new LatticeListener(NodeConfig.port(0))) {
			listener.setConnectionHandler(connection -> message -> {
				int call=calls.incrementAndGet();
				if (call==1) return retry -> {
					assertTrue(Thread.currentThread().isVirtual());
					retries.incrementAndGet();
					entered.countDown();
					try {
						assertTrue(release.await(5,TimeUnit.SECONDS));
					} catch (InterruptedException e) { throw new AssertionError(e); }
					reply(retry,1);
					return true;
				};
				reply(message,call);
				return null;
			});
			listener.launch();
			try (var client=ConvexRemote.connect(listener.getHostAddress())) {
				var first=client.request(Message.createPing(1));
				assertTrue(entered.await(5,TimeUnit.SECONDS));
				var second=client.request(Message.createPing(1));
				assertEquals(1,calls.get());
				release.countDown();
				assertEquals(CVMLong.ONE,first.get(5,TimeUnit.SECONDS).getValue());
				assertEquals(CVMLong.create(2),second.get(5,TimeUnit.SECONDS).getValue());
				assertEquals(1,retries.get());
			}
		} finally { release.countDown(); }
	}
}
