package convex.examples;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.Peer;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.core.exceptions.BadSignatureException;
import convex.etch.EtchStore;
import convex.net.IPUtils;
import convex.peer.API;
import convex.peer.PeerException;
import convex.peer.Server;

public class JoinTestNetwork {
	InetSocketAddress hostAddress=IPUtils.toInetSocketAddress("convex.world:18888");
	AKeyPair kp=AKeyPair.createSeeded(578578); // for user
	Address acct=Address.create(47);
	AccountKey peerKey=kp.getAccountKey();
	
	public void testJoinNetwork() throws IOException, InterruptedException, ExecutionException, TimeoutException, BadSignatureException, PeerException {
		
		System.out.println("PublicKey: "+kp.getAccountKey());

		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.KEYPAIR,kp);
		config.put(Keywords.STORE,EtchStore.create(new File("temp-join-db.etch")));
		config.put(Keywords.CONTROLLER,acct);
		config.put(Keywords.SOURCE,"convex.world:18888");

		Server newServer=API.launchPeer(config);

		// make peer connections directly
		newServer.getConnectionManager().connectToPeer(hostAddress);

		Thread.sleep(10000);
		Peer peer=newServer.getPeer();
		System.out.println("State count:"+peer.getStatePosition());
	}
	
	public static void main(String[] args) throws BadSignatureException, IOException, InterruptedException, ExecutionException, TimeoutException, PeerException {
		new JoinTestNetwork().testJoinNetwork();
	}
}
