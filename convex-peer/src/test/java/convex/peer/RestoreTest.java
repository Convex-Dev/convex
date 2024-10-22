package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.State;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Lists;
import convex.core.data.Maps;
import convex.core.data.Symbols;
import convex.core.exceptions.ResultException;
import convex.core.init.Init;
import convex.core.store.AStore;
import convex.etch.Etch;
import convex.etch.EtchStore;
import convex.etch.EtchUtils;
import convex.etch.EtchUtils.FullValidator;

public class RestoreTest {
	AKeyPair KP=AKeyPair.createSeeded(123456781);
	List<AccountKey> keys=Lists.of(KP.getAccountKey());
	
	State GENESIS=Init.createState(keys);
	Address HERO=Init.GENESIS_ADDRESS;

	@Test
	public void restoreTest() throws InterruptedException, ExecutionException, TimeoutException, ResultException, IOException, PeerException {
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

		AStore store=EtchStore.createTemp();
		Map<Keyword, Object> config = Maps.hashMapOf(
				Keywords.KEYPAIR,KP,
				Keywords.STATE,GENESIS,
				Keywords.STORE,store,
				Keywords.URL,null,
				Keywords.PERSIST,true
		);
		Server s1=API.launchPeer(config);

		// Connect with HERO Account
		Convex cvx1=Convex.connect(s1,HERO,KP);

		Result tx1=cvx1.transactSync(Invoke.create(HERO,1, Symbols.STAR_ADDRESS));
		assertEquals(HERO,tx1.getValue());
		
		Long balance1=cvx1.getBalance(HERO);
		assertTrue(balance1>0);
		s1.close();

		// TODO: testing that server is definitely down. This is a bit slow....
		// assertThrows(Throwable.class,()->cvx1.getBalance(HERO));

		// Launch peer and connect
		config.remove(Keywords.STATE);
		config.remove(Keywords.RESTORE,true);
		Server s2=API.launchPeer(config);
		
		assertNull(s2.getHostname());
		
		Convex cvx2=Convex.connect(s2.getHostAddress(), HERO,KP);

		// TODO: check this?
		Long balance2=cvx2.getBalance(HERO);
		assertEquals(balance1,balance2);

		Result tx2=cvx2.transactSync(Invoke.create(HERO,2, Symbols.BALANCE));
		assertFalse(tx2.isError());
		cvx2.close();

		State state=s2.getPeer().getConsensusState();
		assertNotNull(state);
		
		Etch e=((EtchStore)s2.getStore()).getEtch();
		FullValidator vd = EtchUtils.getFullValidator();
		e.visitIndex(vd);
	}
}