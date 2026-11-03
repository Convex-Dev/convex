package convex.node;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.auth.did.DID;
import convex.auth.did.DIDKeyAuthorizer;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.core.message.AConnection;
import convex.core.message.ChallengeSigner;
import convex.core.message.Message;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.lattice.LatticeHost;
import convex.lattice.generic.MaxLattice;

/**
 * Propagator groups given only challenge signers authenticate routes both ways, and
 * the clients they dial prove the group's key in the lattice challenge context only.
 */
public class LatticeChallengeSignerTest {

	private static ChallengeSigner latticeSigner(AKeyPair keyPair) {
		return ChallengeSigner.create(keyPair,Message.LATTICE_PEER_CHALLENGE_CONTEXT);
	}

	@Test
	public void testRoutesAuthenticateWithSignersOnly() throws Exception {
		var aliceKey=AKeyPair.generate();
		var bobKey=AKeyPair.generate();
		var aliceStore=new MemoryStore();
		var bobStore=new MemoryStore();
		var lattice=MaxLattice.create();
		var aliceGroup=new LatticePropagator(aliceStore,LatticePropagatorConfig.create());
		var bobGroup=new LatticePropagator(bobStore,LatticePropagatorConfig.create());
		aliceGroup.setTransportSigner(latticeSigner(aliceKey));
		bobGroup.setTransportSigner(latticeSigner(bobKey));
		assertEquals(aliceKey.getAccountKey(),aliceGroup.getTransportKey());
		var incoming=new CompletableFuture<AConnection>();
		try (var alice=new NodeServer<>(RelayHost.open(lattice,aliceStore),NodeConfig.port(-1));
			var bob=new NodeServer<>(RelayHost.open(lattice,bobStore),NodeConfig.localNetwork());
			var listener=new LatticeListener(NodeConfig.localNetwork())) {
			alice.addPropagator(aliceGroup);
			bob.addPropagator(bobGroup);
			listener.registerPropagator(bobGroup);
			listener.setSelector(connection -> { incoming.complete(connection); return bobGroup; });
			alice.launch();
			bob.launch();
			listener.launch();

			// Alice proves Bob's key as she dials him, then Bob proves hers over her socket
			var bobManager=bobGroup.getConnectionManager();
			bobManager.addPeer(aliceKey.getAccountKey());
			Convex dialled=aliceGroup.getConnectionManager().connectPeer(bobKey.getAccountKey(),
				new InetSocketAddress("localhost",listener.getPort())).get(5,TimeUnit.SECONDS);
			assertEquals(bobKey.getAccountKey(),dialled.getVerifiedPeer());
			assertNull(dialled.getKeyPair(),"a dialled client gets the group's signer, not a key pair");
			AConnection fromAlice=incoming.get(5,TimeUnit.SECONDS);
			bobGroup.authenticateInboundRoute(fromAlice,aliceKey.getAccountKey());
			bobManager.whenInboundConnectionUpgraded(aliceKey.getAccountKey()).get(5,TimeUnit.SECONDS);

			// Her client answers a lattice challenge, and refuses the same one in any other context
			Hash token=Blob.createRandom(new Random(5),16).getHash();
			Result answered=challenge(fromAlice,bobStore,Message.signChallenge(bobKey,token,
				aliceKey.getAccountKey(),Message.LATTICE_PEER_CHALLENGE_CONTEXT));
			assertEquals(aliceKey.getAccountKey(),Message.verifyChallengeResponse(answered,token,
				bobKey.getAccountKey(),Message.LATTICE_PEER_CHALLENGE_CONTEXT,aliceKey.getAccountKey()));
			Result foreign=challenge(fromAlice,bobStore,Message.signChallenge(bobKey,token,
				aliceKey.getAccountKey(),CVMLong.create(42)));
			assertEquals(ErrorCodes.TRUST,foreign.getErrorCode(),
				"a challenge in another context must be refused: "+foreign);
		}
	}

	/** Sends a challenge over a connection and returns the answer. */
	private static Result challenge(AConnection connection,AStore store,SignedData<ACell> challenge) throws Exception {
		Message reply=connection.request(Message.createChallenge(null,challenge),0,new CompletableFuture<>())
			.get(5,TimeUnit.SECONDS);
		reply.getPayload(store);
		return reply.toResult();
	}

	@Test
	public void testSignersMustBeForTheLatticeContext() {
		var other=ChallengeSigner.create(AKeyPair.generate(),CVMLong.create(42));
		var group=new LatticePropagator(new MemoryStore(),LatticePropagatorConfig.create());
		assertThrows(IllegalArgumentException.class,() -> group.setTransportSigner(other));
		assertNull(group.getTransportKey());
		assertThrows(IllegalArgumentException.class,() -> group.getConnectionManager().setChallengeSigner(other));
	}

	@Test
	public void testNodeGivesNewGroupsItsSigner() throws Exception {
		var key=AKeyPair.generate();
		var did=DID.forKey(key.getAccountKey());
		try (var store=new MemoryStore();
				var network=new LatticeNode<>(LatticeHost.create(CVMLong.ONE,store),NodeConfig.port(-1))) {
			network.setIdentity(did,latticeSigner(key),DIDKeyAuthorizer.CONVEX);
			assertNull(network.getKeyPair(),"the node is given no key pair");
			assertEquals(did,network.getDID());
			assertEquals(key.getAccountKey(),network.createPropagator().getTransportKey());
			assertThrows(IllegalArgumentException.class,
				() -> network.setIdentity(did,ChallengeSigner.create(key,null),DIDKeyAuthorizer.CONVEX));
			assertThrows(IllegalArgumentException.class,
				() -> network.setIdentity(did,latticeSigner(AKeyPair.generate()),DIDKeyAuthorizer.CONVEX));
		}
	}
}
