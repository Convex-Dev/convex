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
import convex.core.data.AVector;
import convex.core.data.Blobs;
import convex.core.data.Sets;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.message.MessageType;
import convex.core.store.MemoryStore;
import convex.core.util.ThreadUtils;
import convex.lattice.LatticeApplication;
import convex.lattice.LatticeHost;
import convex.lattice.generic.SetLattice;

/** Service shutdown: stop ingress, sync drafts, close the host, then close the node (#778). */
class NodeShutdownTest {

	/**
	 * Work accepted before {@link NodeServer#stopIngress()} lands before it returns;
	 * afterwards nothing from networking changes the host, while host syncs are still
	 * published. So drafts synced next include every accepted merge.
	 */
	@Test void stopIngressLandsAcceptedWorkThenKeepsPublishing() throws Exception {
		BlockingWriteStore ingressStore=new BlockingWriteStore("Lattice propagator ingress");
		try (var primaryStore=new MemoryStore()) {
			LatticeHost<ASet<ACell>> host=RelayHost.open(SetLattice.create(),primaryStore);
			NodeServer<ASet<ACell>> node=new NodeServer<>(host,NodeConfig.port(0));
			LatticePropagator primary=new LatticePropagator(primaryStore);
			LatticePropagator ingress=new LatticePropagator(ingressStore);
			node.addPropagator(primary);
			node.addPropagator(ingress);
			LatticeListener listener=new LatticeListener(NodeConfig.port(0));
			listener.registerPropagator(ingress);
			listener.setSelector(connection -> ingress);
			try {
				node.launch();
				listener.launch();
				try (ConvexRemote source=ConvexRemote.connect(listener.getHostAddress())) {
					AVector<?> payload=Vectors.create(MessageTag.LATTICE_VALUE,null,Vectors.empty(),
						Sets.of(CVMLong.create(778)));
					assertTrue(source.trySend(Message.create(MessageType.LATTICE_VALUE,payload,payload.getEncoding())));
					assertTrue(ingressStore.entered.await(5,TimeUnit.SECONDS),
						"the ingress worker should hold the accepted message");
				}

				listener.close();
				CompletableFuture<Void> stopping=CompletableFuture.runAsync(node::stopIngress,
					ThreadUtils.getVirtualExecutor());
				assertFalse(stopping.isDone(),"stopping ingress waits for accepted work");
				ingressStore.release();
				stopping.get(10,TimeUnit.SECONDS);
				assertTrue(node.getLocalValue().contains(CVMLong.create(778)),
					"accepted work lands before stopIngress returns");
				assertFalse(node.isRunning());

				assertEquals(NodeServer.MergeOutcome.REJECTED,
					node.mergeInbound(new ACell[0],Sets.of(CVMLong.create(779))));
				assertFalse(node.getLocalValue().contains(CVMLong.create(779)),
					"networking must not change the host after stopIngress");

				CompletableFuture<ACell> announced=primary.nextAnnounce();
				host.cursor().updateAndGet(set -> set.include(CVMLong.create(780)));
				host.sync();
				assertTrue(((ASet<?>)announced.get(10,TimeUnit.SECONDS)).contains(CVMLong.create(780)),
					"publication continues until the node closes");

				node.close();
				assertEquals(NodeServer.LifecycleState.STOPPED,node.getLifecycleState());
			} finally {
				ingressStore.release();
				listener.close();
				node.close();
				ingressStore.close();
			}
		}
	}

	/**
	 * A draft synced after ingress stops is saved by the host's final checkpoint, which
	 * the still-attached node sends to a connected peer as it closes.
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
			var app=senderHost.addApplication(new LatticeApplication<>(lattice,senderHost));
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

				sender.stopIngress();
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
