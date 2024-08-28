package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.core.Constants;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.exceptions.BadSignatureException;
import convex.core.exceptions.ResultException;
import convex.core.lang.RT;
import convex.core.transactions.Invoke;
import convex.etch.EtchStore;

public class JoinNetworkTest {
	private static TestNetwork network;
	
	@BeforeAll
	public static void init() {
		network = TestNetwork.getInstance();
	}

	@Test
	public void testJoinNetwork() throws IOException, InterruptedException, ExecutionException, TimeoutException, BadSignatureException, ResultException, PeerException {
		AKeyPair kp=AKeyPair.generate();
		AccountKey peerKey=kp.getAccountKey();

		// We plan to stake twice the minimum amount
		long STAKE=Constants.MINIMUM_EFFECTIVE_STAKE*2;
		
		synchronized(network.SERVER) {
			Convex heroConvex=network.CONVEX;

			// Create new peer controller account, ensure it has enough coins to stake
			Address controller=heroConvex.createAccountSync(kp.getAccountKey());
			Result trans=heroConvex.transferSync(controller,STAKE*2);
			assertFalse(trans.isError());

			// create test user account
			Address user=heroConvex.createAccountSync(kp.getAccountKey());
			trans=heroConvex.transferSync(user,STAKE);
			assertFalse(trans.isError());

			Convex convex=Convex.connect(network.SERVER.getHostAddress(), controller, kp);
			Result cresult=convex.transactSync(Invoke.create(controller, 0, "(create-peer "+peerKey+" "+STAKE+")"));
			assertFalse(cresult.isError(),()->"Failed to create peer: "+cresult.toString());
			assertEquals(RT.cvm(STAKE),trans.getValue());
			//Thread.sleep(1000); // sleep a bit to allow background stuff

			HashMap<Keyword,Object> config=new HashMap<>();
			config.put(Keywords.KEYPAIR,kp);
			config.put(Keywords.STORE,EtchStore.createTemp());
			config.put(Keywords.CONTROLLER,controller);
			config.put(Keywords.SOURCE,network.SERVER.getHostAddress());

			Server newServer=API.launchPeer(config);

			// make peer connections directly
			newServer.getConnectionManager().connectToPeer(network.SERVER.getHostAddress());
			network.SERVER.getConnectionManager().connectToPeer(newServer.getHostAddress());

			// TODO: should these be in consensus at this point since just synced
			// note: shouldn't matter which is the current store
			// assertEquals(newServer.getPeer().getConsensusState(),network.SERVER.getPeer().getConsensusState());
			// Thread.sleep(100);
			
			Convex client=Convex.connect(newServer.getHostAddress(), user, kp);
			client.setNextSequence(1); // avoids a potential stale query
			
			// TODO: this failed for some people, why is it even here?
			// assertEquals(ErrorCodes.NOBODY,client.transactSync("*address*").getErrorCode());
			
			Result r=client.requestStatus().get(10000,TimeUnit.MILLISECONDS);
			assertFalse(r.isError());
		}
	}

}
