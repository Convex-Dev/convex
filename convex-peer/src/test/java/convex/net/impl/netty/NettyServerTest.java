package convex.net.impl.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.api.Convex;
import convex.core.Result;
import convex.core.cvm.Address;
import convex.core.lang.RT;
import convex.net.Message;

@TestInstance(Lifecycle.PER_CLASS)
public class NettyServerTest {

	@Test public void testServerSetup() throws IOException, InterruptedException, TimeoutException {
		try (NettyServer server = new NettyServer(0)) {
			server.launch();
			Integer port=server.getPort();
			
			assertNotNull(port);
			InetSocketAddress addr=server.getHostAddress();
			assertEquals(port,addr.getPort());
			
			CompletableFuture<Message> rec=new CompletableFuture<>();
			NettyConnection client=NettyConnection.connect(addr, m->{
				rec.complete(m);
			});
			
			client.send(Message.createQuery(10, "*address*", Address.create(17)));
			
			Message m=rec.join();
			assertEquals(RT.cvm(10),m.getResultID());
			
			Convex convex=Convex.connect(addr);
			Result r=convex.query(":hello").join();
			assertFalse(r.isError());
			
		}
	}
}
