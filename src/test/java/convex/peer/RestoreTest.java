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
import convex.core.Init;
import convex.core.Result;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Maps;
import convex.core.lang.Symbols;
import convex.core.store.AStore;
import convex.core.transactions.Invoke;
import etch.EtchStore;

public class RestoreTest {

	@Test 
	public void restoreTest() throws IOException, InterruptedException, ExecutionException, TimeoutException {
		AKeyPair kp=Init.KEYPAIRS[0];
		AStore store=EtchStore.createTemp();
		Map<Keyword, Object> config = Maps.hashMapOf(
				Keywords.KEYPAIR,kp,
				Keywords.STORE,store
		);
		Server s1=API.launchPeer(config);
		
		// Connect with HERO Account
		Convex cvx1=Convex.connect(s1.getHostAddress(), Init.HERO,Init.HERO_KP);
		
		Result tx1=cvx1.transactSync(Invoke.create(Init.HERO,1, Symbols.STAR_ADDRESS));
		assertEquals(Init.HERO,tx1.getValue());
		Long balance1=cvx1.getBalance(Init.HERO);
		assertTrue(balance1>0);
		s1.close();
		
		// TODO: testing that server is definitely down
		// assertThrows(IOException.class,()->Convex.connect(s1.getHostAddress(), Init.HERO_KP));
		// assertThrows(IOException.class,()->cvx1.getBalance(Init.HERO));
		
		// Launch peer and connect
		Server s2=API.launchPeer(config);
		Convex cvx2=Convex.connect(s2.getHostAddress(), Init.HERO,Init.HERO_KP);
		
		Long balance2=cvx2.getBalance(Init.HERO);
		assertEquals(balance1,balance2);
		
		Result tx2=cvx2.transactSync(Invoke.create(Init.HERO,2, Symbols.BALANCE));
		assertFalse(tx2.isError());
		
		State state=s2.getPeer().getConsensusState();
		assertNotNull(state);
	}
}
