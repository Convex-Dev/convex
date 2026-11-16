package convex.observer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.Keyword;
import convex.core.init.Init;
import convex.core.store.MemoryStore;
import convex.peer.API;
import convex.peer.Server;

/** A Kafka observer lives as long as the Server it observes. */
public class StrimziKafkaTest {

	private static Server launchPeer() throws Exception {
		AKeyPair kp=AKeyPair.generate();
		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.KEYPAIR,kp);
		config.put(Keywords.STATE,Init.createState(List.of(kp.getAccountKey())));
		config.put(Keywords.PORT,0);
		config.put(Keywords.STORE,new MemoryStore());
		return API.launchPeer(config);
	}

	@Test
	public void anObserverClosesWithItsServer() throws Exception {
		Server server=launchPeer();
		Thread queueThread;
		try {
			StrimziKafka observer=StrimziKafka.get(server);
			assertSame(observer,StrimziKafka.get(server));
			queueThread=observer.getThread();
			assertNotNull(queueThread);
		} finally {
			server.close();
		}
		// The thread ends only after the registry has forgotten the observer
		queueThread.join(5000);
		assertFalse(queueThread.isAlive(),"the observer's thread outlived its server");
		assertFalse(StrimziKafka.isObserving(server),"the observer of a closed server is still registered");
	}

	@Test
	public void anObserverOfAClosedServerClosesAtOnce() throws Exception {
		Server server=launchPeer();
		server.close();
		StrimziKafka observer=StrimziKafka.get(server);
		// Null once the observer has closed, which it does after the registry forgets it
		Thread queueThread=observer.getThread();
		if (queueThread!=null) queueThread.join(5000);
		assertTrue(queueThread==null || !queueThread.isAlive(),"the observer of a closed server kept running");
		assertFalse(StrimziKafka.isObserving(server));
	}
}
