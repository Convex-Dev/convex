package convex.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import convex.api.Convex;
import convex.core.Coin;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.exceptions.BadSignatureException;
import convex.core.lang.RT;
import convex.core.transactions.Invoke;
import convex.core.util.Utils;
import convex.peer.API;
import convex.peer.Server;
import etch.EtchStore;

public class JoinTestNetwork {
	InetSocketAddress hostAddress=Utils.toInetSocketAddress("https://convex.world");
	AKeyPair kp=AKeyPair.generate();
	AccountKey peerKey=kp.getAccountKey();
	
	public void testJoinNetwork() throws IOException, InterruptedException, ExecutionException, TimeoutException, BadSignatureException {
		long STAKE=1000000000;
		Convex heroConvex=Convex.connect(hostAddress);
		
		// Create new peer controller account
		Address controller=heroConvex.createAccountSync(kp.getAccountKey());
		Result trans=heroConvex.transferSync(controller,Coin.DIAMOND);
		assertFalse(trans.isError());

		// create test user account
		Address user=heroConvex.createAccountSync(kp.getAccountKey());
		trans=heroConvex.transferSync(user,STAKE);
		assertFalse(trans.isError());

		Convex convex=Convex.connect(hostAddress, user, kp);
		trans=convex.transactSync(Invoke.create(controller, 0, "(create-peer "+peerKey+" "+STAKE+")"));
		assertEquals(RT.cvm(STAKE),trans.getValue());
		Thread.sleep(1000); // sleep a bit to allow ServerTest to confirm and write new consensus

		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.KEYPAIR,kp);
		config.put(Keywords.STORE,EtchStore.createTemp());
		config.put(Keywords.SOURCE,"https://convex.world");

		Server newServer=API.launchPeer(config);

		// make peer connections directly
		newServer.getConnectionManager().connectToPeer(hostAddress);


		Convex client=Convex.connect(newServer.getHostAddress(), user, kp);
		assertEquals(user,client.transactSync(Invoke.create(user, 0, "*address*")).getValue());
	}
	
	public static void main(String[] args) {
		
	}
}
