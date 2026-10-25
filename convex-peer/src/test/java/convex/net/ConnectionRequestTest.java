package convex.net;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import convex.api.ConvexRemote;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.message.Message;
import convex.net.impl.netty.NettyServer;
import convex.net.impl.nio.NIOServer;

/** Requests with no timeout must still finish when either physical endpoint closes. */
@SuppressWarnings("deprecation")
class ConnectionRequestTest {

	private static class TestNIOServer extends NIOServer {
		TestNIOServer(Consumer<Message> receiver) { super(receiver); }
	}

	@TestFactory
	java.util.stream.Stream<DynamicTest> disconnects() {
		return java.util.stream.IntStream.range(0,8).mapToObj(i -> {
			boolean nettyServer=(i&1)!=0,nettyClient=(i&2)!=0,closeClient=(i&4)!=0;
			String name="server="+(nettyServer ? "Netty" : "NIO")+", client="+(nettyClient ? "Netty" : "NIO")+", close="+(closeClient ? "client" : "server");
			return DynamicTest.dynamicTest(name,() -> disconnectCompletesRequestsInBothDirections(nettyServer,nettyClient,closeClient));
		});
	}

	private void disconnectCompletesRequestsInBothDirections(boolean nettyServer,boolean nettyClient,boolean closeClient) throws Exception {
		CompletableFuture<Message> received=new CompletableFuture<>();
		AServer server;
		if (nettyServer) {
			NettyServer s=new NettyServer(0);
			s.setReceiveAction(received::complete);
			server=s;
		} else {
			server=new TestNIOServer(received::complete);
			server.setPort(0);
		}
		try (server) {
			server.launch();
			try (ConvexRemote client=nettyClient ? ConvexRemote.connectNetty(server.getHostAddress()) : ConvexRemote.connectNIO(server.getHostAddress())) {
				client.setTimeout(0);
				var outbound=client.requestStatus();
				var inbound=received.get(5,TimeUnit.SECONDS).getConnection();
				// The client ignores this unsolicited STATUS request, leaving a waiter
				// on the server end as well. Neither waiter has an expiry timer.
				var reverse=inbound.request(Message.createStatusRequest(null),0);
				assertFalse(outbound.isDone());
				assertFalse(reverse.isDone());
				if (closeClient) client.close();
				else inbound.close();
				Result result=outbound.get(5,TimeUnit.SECONDS);
				assertTrue(result.isError());
				assertNotEquals(ErrorCodes.TIMEOUT,result.getErrorCode());
				assertThrows(ExecutionException.class,() -> reverse.get(5,TimeUnit.SECONDS));
				assertFalse(inbound.hasPendingRequests());
				assertFalse(client.hasPendingRequests());
			}
		}
	}
}
