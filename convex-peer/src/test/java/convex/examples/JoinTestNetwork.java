package convex.examples;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import convex.core.Peer;
import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.exceptions.BadSignatureException;
import convex.core.util.Utils;
import convex.peer.API;
import convex.peer.Server;
import etch.EtchStore;

public class JoinTestNetwork {
	InetSocketAddress hostAddress=Utils.toInetSocketAddress("convex.world:43579");
	AKeyPair kp=AKeyPair.createSeeded(578578); // for user
	Address acct=Address.create(47);
	AccountKey peerKey=kp.getAccountKey();
	
	public void testJoinNetwork() throws IOException, InterruptedException, ExecutionException, TimeoutException, BadSignatureException {
		
		System.out.println("PublicKey: "+kp.getAccountKey());

		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.KEYPAIR,kp);
		config.put(Keywords.STORE,EtchStore.createTemp());
		config.put(Keywords.CONTROLLER,acct);
		config.put(Keywords.SOURCE,"convex.world:43579");

		Server newServer=API.launchPeer(config);

		// make peer connections directly
		newServer.getConnectionManager().connectToPeer(hostAddress);

		Thread.sleep(10000);
		Peer peer=newServer.getPeer();
		System.out.println("State count:"+peer.getStates().count());
	}
	
	public static void main(String[] args) throws BadSignatureException, IOException, InterruptedException, ExecutionException, TimeoutException {
		new JoinTestNetwork().testJoinNetwork();
	}
}
