package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.core.Result;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Maps;
import convex.core.init.InitConfigTest;
import convex.core.lang.Symbols;
import convex.core.store.AStore;
import convex.core.transactions.Invoke;
import etch.EtchStore;

public class RestoreTest {

	@Test
	public void restoreTest() throws IOException, InterruptedException, ExecutionException, TimeoutException {
//		 {
//		   System.out.println("Test store = "+Stores.current());
//
//		   State s=Init.STATE;
//		   System.out.println("Init Ref = "+s.getRef());
//
//		   Ref<State> ref=Ref.forHash(s.getHash());
//		   if (ref.isMissing()) {
//			   System.out.println("State not stored");
//		   } else {
//			   State s2=ref.getValue();
//			   System.out.println("Store ref: "+s2.getRef());
//		   }
//		}

		AKeyPair kp=InitConfigTest.FIRST_PEER_KEYPAIR;
		AStore store=EtchStore.createTemp();
		Map<Keyword, Object> config = Maps.hashMapOf(
				Keywords.KEYPAIR,kp,
				Keywords.STORE,store,
				Keywords.PERSIST,true
		);
		Server s1=API.launchPeer(config);

		// Connect with HERO Account
		Convex cvx1=Convex.connect(s1.getHostAddress(), InitConfigTest.HERO_ADDRESS,InitConfigTest.HERO_KEYPAIR);

		Result tx1=cvx1.transactSync(Invoke.create(InitConfigTest.HERO_ADDRESS,1, Symbols.STAR_ADDRESS));
		assertEquals(InitConfigTest.HERO_ADDRESS,tx1.getValue());
		Long balance1=cvx1.getBalance(InitConfigTest.HERO_ADDRESS);
		assertTrue(balance1>0);
		s1.close();

		// TODO: testing that server is definitely down
		// assertThrows(IOException.class,()->Convex.connect(s1.getHostAddress(), Init.HERO_KP));
		// assertThrows(IOException.class,()->cvx1.getBalance(Init.HERO));

		// Launch peer and connect
		Server s2=API.launchPeer(config);
		Convex cvx2=Convex.connect(s2.getHostAddress(), InitConfigTest.HERO_ADDRESS,InitConfigTest.HERO_KEYPAIR);

		Long balance2=cvx2.getBalance(InitConfigTest.HERO_ADDRESS);
		assertEquals(balance1,balance2);

		Result tx2=cvx2.transactSync(Invoke.create(InitConfigTest.HERO_ADDRESS,2, Symbols.BALANCE));
		assertFalse(tx2.isError());

		State state=s2.getPeer().getConsensusState();
		assertNotNull(state);
	}
}
