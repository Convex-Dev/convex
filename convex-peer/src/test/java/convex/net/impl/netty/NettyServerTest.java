package convex.net.impl.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.parallel.*;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.Result;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Vectors;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.RT;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.store.NullStore;

@Execution(ExecutionMode.CONCURRENT)
@TestInstance(Lifecycle.PER_CLASS)
public class NettyServerTest {

	@Test public void testServerSetup() throws IOException, InterruptedException, TimeoutException, BadFormatException {
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
			
			{ // Regular client
				Convex convex=Convex.connect(addr);
				Result r=convex.query(Keywords.FOO).join();
				assertFalse(r.isError());
				AVector<?> v= RT.ensureVector(r.getValue());
				
				AVector<?> expected=Vectors.of(MessageTag.QUERY,0,Keywords.FOO,null);
				assertEquals(expected,v);
			}
			
			{ // Netty client
				Convex convex=ConvexRemote.connectNetty(addr);
				Result r=convex.query(":hello").join();
				assertFalse(r.isError());
			}
		}
	}
	
	@Test public void testBigMessage() throws IOException, InterruptedException, TimeoutException, BadFormatException {
		try (NettyServer server = new NettyServer(0)) {
			server.launch();
			server.setReceiveAction(m->{
				Result r;
				try {
					m.getPayload(NullStore.INSTANCE);
					r = Result.create(m.getRequestID(), m.getPayload(), null);
				} catch (BadFormatException e) {
					throw new Error("Bad format",e);
				}
				m.returnResult(r);
			});
			Integer port=server.getPort();
			
			assertNotNull(port);
			InetSocketAddress socketAddr=server.getHostAddress();
			assertEquals(port,socketAddr.getPort());
			
			ArrayBlockingQueue<Message> queue=new ArrayBlockingQueue<>(100);
		
			NettyConnection client=NettyConnection.connect(socketAddr, m->{
				queue.add(m);
			});
			
			Blob blob=Blobs.createRandom(100000).toFlatBlob();
			
			Message mq=Message.createQuery(10, blob, Address.create(17));
			client.send(mq);
			
			Message m=queue.poll(1000,TimeUnit.MILLISECONDS);
			assertEquals(RT.cvm(10),m.getResultID());
			

		}
	}
}
