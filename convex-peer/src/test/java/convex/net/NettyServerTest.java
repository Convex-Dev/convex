package convex.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.cvm.Address;

@TestInstance(Lifecycle.PER_CLASS)
public class NettyServerTest {

	@Test public void testServerSetup() throws IOException, InterruptedException {
		try (NettyServer server = new NettyServer(0)) {
			server.launch();
			Integer port=server.getPort();
			
			assertNotNull(port);
			InetSocketAddress addr=server.getHostAddress();
			assertEquals(port,addr.getPort());
			
			ArrayList<Message> rec=new ArrayList<>();
			NettyClient client=NettyClient.connect(addr, m->{
				rec.add(m);
			});
			
			client.send(Message.createQuery(10, "*address*", Address.create(17)));
			
			// assertEquals(1,rec.size());
		}
	}
}
