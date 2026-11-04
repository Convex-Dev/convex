package convex.node;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.api.ConvexRemote;
import convex.auth.did.DID;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.Blobs;
import convex.core.store.MemoryStore;
import convex.lattice.LatticeComponent;
import convex.lattice.LatticeHost;
import convex.lattice.generic.SetLattice;

/** Service shutdown: sync drafts, close the host, then close the node (#778). */
class NodeShutdownTest {

	/**
	 * A draft synced at shutdown is saved by the host's final checkpoint, which the
	 * still-attached node sends to a connected peer as it closes.
	 */
	@Test void finalCheckpointReachesConnectedPeer() throws Exception {
		AKeyPair senderKey=AKeyPair.createSeeded(7781);
		AKeyPair receiverKey=AKeyPair.createSeeded(7782);
		// Writing this to the socket outlasts a close that does not wait for it
		ACell draft=Blobs.createRandom(new Random(778),3_000_000);
		SetLattice<ACell> lattice=SetLattice.create();
		try (var senderStore=new MemoryStore();
				var receiverStore=new MemoryStore()) {
			LatticeHost<ASet<ACell>> senderHost=LatticeHost.open(lattice.zero(),senderStore);
			var app=senderHost.addApplication(new LatticeComponent<>(lattice,senderHost.cursor().fork()));
			LatticeHost<ASet<ACell>> receiverHost=RelayHost.open(lattice,receiverStore);
			try (var receiver=new LatticeNode<>(receiverHost,NodeConfig.port(0));
					var sender=new LatticeNode<>(senderHost,NodeConfig.port(0))) {
				receiver.setIdentity(DID.forKey(receiverKey.getAccountKey()),receiverKey).launch();
				LatticePropagator receiverGroup=receiver.createPropagator();
				receiver.serveAllInbound(receiverGroup);
				CompletableFuture<ACell> received=new CompletableFuture<>();
				receiverGroup.setInboundLatticeListener((connection,group,path,value,changed) -> {
					if (value instanceof ASet<?> set && set.contains(draft)) received.complete(value);
				});

				sender.setIdentity(DID.forKey(senderKey.getAccountKey()),senderKey).launch();
				LatticePropagator senderGroup=sender.createPropagator();
				ConvexRemote route=ConvexRemote.connect(receiver.getHostAddress());
				senderGroup.addPeer(receiverGroup.getTransportKey(),route).get(10,TimeUnit.SECONDS);

				app.cursor().updateAndGet(set -> set.include(draft));
				app.sync();
				assertFalse(senderStore.<ASet<ACell>>getRootData().contains(draft),
					"application sync alone neither saves nor publishes");
				senderHost.close();
				assertTrue(senderStore.<ASet<ACell>>getRootData().contains(draft));
				sender.close();

				received.get(10,TimeUnit.SECONDS);
				assertTrue(receiverHost.cursor().get().contains(draft));
			}
		}
	}
}
