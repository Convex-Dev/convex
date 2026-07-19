package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.core.Result;
import convex.core.cpos.Block;
import convex.core.cpos.CPoSConstants;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.Peer;
import convex.core.cvm.State;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadSignatureException;
import convex.core.exceptions.ResultException;
import convex.core.init.Init;
import convex.core.lang.RT;
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
		long STAKE=CPoSConstants.MINIMUM_EFFECTIVE_STAKE*2;
		
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

			HashMap<Keyword,Object> config=new HashMap<>();
			config.put(Keywords.KEYPAIR,kp);
			config.put(Keywords.STORE,EtchStore.createTemp()); // Etch store as per real peer startup
			config.put(Keywords.CONTROLLER,controller);
			config.put(Keywords.SOURCE,network.SERVER.getHostAddress());

			Server newServer=API.launchPeer(config);

			// make peer connections directly
			newServer.getConnectionManager().connectToPeer(network.SERVER.getHostAddress()).join();
			network.SERVER.getConnectionManager().connectToPeer(newServer.getHostAddress()).join();

			// TODO: should these be in consensus at this point since just synced
			// note: shouldn't matter which is the current store
			// assertEquals(newServer.getPeer().getConsensusState(),network.SERVER.getPeer().getConsensusState());

			Convex client=Convex.connect(newServer.getHostAddress(), user, kp);
			client.setNextSequence(1); // avoids a potential stale query
			
			// TODO: this failed for some people, why is it even here?
			// assertEquals(ErrorCodes.NOBODY,client.transactSync("*address*").getErrorCode());
			
			Result r=client.requestStatus().get(10000,TimeUnit.MILLISECONDS);
			assertFalse(r.isError());
		}
	}

	@Test
	public void testJoinReplaysInsteadOfAdoptingRemoteState() throws Exception {
		AKeyPair sourceKeyPair=AKeyPair.createSeeded(987654321);
		State genesis=Init.createState(List.of(sourceKeyPair.getAccountKey()));
		Peer correct=Peer.create(sourceKeyPair,genesis);
		Block block=Block.of(correct.getTimestamp(),sourceKeyPair.signData(
				Invoke.create(Init.GENESIS_ADDRESS,1,"*address*")));
		correct=correct.proposeBlock(block)
				.mergeBeliefs().mergeBeliefs().mergeBeliefs().mergeBeliefs()
				.updateState();
		assertEquals(1,correct.getStatePosition());

		Server source=null;
		Server destination=null;
		try {
			HashMap<Keyword,Object> sourceConfig=new HashMap<>();
			sourceConfig.put(Keywords.KEYPAIR,sourceKeyPair);
			sourceConfig.put(Keywords.STATE,genesis);
			sourceConfig.put(Keywords.STORE,EtchStore.createTemp());
			sourceConfig.put(Keywords.PORT,0);
			source=API.launchPeer(sourceConfig);

			State corruptState=correct.getConsensusState().withTimestamp(
					correct.getConsensusState().getTimestamp().longValue()+1);
			AMap<Keyword,ACell> corruptData=correct.toData().assoc(Keywords.STATE,corruptState);
			Peer advertised=Peer.fromData(sourceKeyPair,corruptData);
			source.getCVMExecutor().setPeer(advertised);
			source.getCVMExecutor().persistPeerData();
			try (Convex sourceClient=Convex.connect(source.getHostAddress())) {
				Result statusResult=sourceClient.requestStatus().get(10,TimeUnit.SECONDS);
				assertFalse(statusResult.isError(),()->"Source status failed: "+statusResult);
				AMap<Keyword,ACell> status=API.ensureStatusMap(statusResult.getValue());
				assertNotNull(status,"Source returned an invalid status payload");
				assertEquals(CVMLong.create(correct.getStatePosition()),status.get(Keywords.STATE_POSITION));
			}

			AKeyPair destinationKeyPair=AKeyPair.createSeeded(987654322);
			HashMap<Keyword,Object> destinationConfig=new HashMap<>();
			destinationConfig.put(Keywords.KEYPAIR,destinationKeyPair);
			destinationConfig.put(Keywords.STORE,EtchStore.createTemp());
			destinationConfig.put(Keywords.PORT,0);
			destinationConfig.put(Keywords.SOURCE,source.getHostAddress());
			destination=API.launchPeer(destinationConfig);

			assertEquals(correct.getStatePosition(),destination.getPeer().getStatePosition());
			assertEquals(correct.getConsensusState().getHash(),
					destination.getPeer().getConsensusState().getHash());
			assertNotEquals(advertised.getConsensusState().getHash(),
					destination.getPeer().getConsensusState().getHash());
		} finally {
			if (destination!=null) destination.close();
			if (source!=null) source.close();
		}
	}

}
