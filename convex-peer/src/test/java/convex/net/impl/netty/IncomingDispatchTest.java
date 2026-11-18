package convex.net.impl.netty;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import convex.core.data.Blob;
import convex.core.message.LocalConnection;
import convex.core.message.Message;
import convex.net.IncomingHandler;

class IncomingDispatchTest {

	@Test void dispatchDoesNotDecodeOrAllocatePerMessage() throws Exception {
		Class<?> management=Class.forName("java.lang.management.ManagementFactory");
		Object bean=management.getMethod("getThreadMXBean").invoke(null);
		Class<?> extended=Class.forName("com.sun.management.ThreadMXBean");
		assumeTrue(extended.isInstance(bean));
		assumeTrue((Boolean)extended.getMethod("isThreadAllocatedMemorySupported").invoke(bean));
		extended.getMethod("setThreadAllocatedMemoryEnabled",boolean.class).invoke(bean,true);
		Method allocated=extended.getMethod("getThreadAllocatedBytes",long.class);
		long thread=Thread.currentThread().threadId();
		var calls=new AtomicInteger();
		var connection=LocalConnection.createPair(message -> true,message -> true);
		var delivery=new NettyServer.AcceptedConnection(null);
		delivery.connection=connection;
		delivery.open(message -> { calls.incrementAndGet(); return null; });
		// Not a valid payload: the routing layer must not decode it without a request to match.
		Message message=Message.create(connection,Blob.fromHex("ff"));
		for (int i=0;i<200_000;i++) delivery.apply(message);
		long before=(Long)allocated.invoke(bean,thread);
		for (int i=0;i<1_000_000;i++) delivery.apply(message);
		long bytes=(Long)allocated.invoke(bean,thread)-before;
		assertEquals(1_200_000,calls.get());
		assertTrue(bytes<4096,"Dispatch allocated "+bytes+" bytes for 1,000,000 messages");
		delivery.accept(connection);
		connection.close();
	}

	@Test void synchronousFactoryCloseStillReleasesReturnedHandler() {
		var closed=new AtomicInteger();
		var disconnected=new AtomicInteger();
		var delivery=new NettyServer.AcceptedConnection(connection -> disconnected.incrementAndGet());
		var connection=LocalConnection.createPair(message -> true,message -> true);
		delivery.connection=connection;
		delivery.accept(connection);
		delivery.open(new IncomingHandler() {
			@Override public Predicate<Message> handle(Message message) { fail("Closed connection must not receive messages"); return null; }
			@Override public void close() { closed.incrementAndGet(); }
		});
		delivery.accept(connection);
		assertEquals(1,closed.get());
		assertEquals(1,disconnected.get());
		connection.close();
	}
}
