package convex.node;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.api.Acquiror;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Sets;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.message.AConnection;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.message.MessageType;
import convex.core.store.MemoryStore;
import convex.etch.EtchStore;
import convex.lattice.generic.SetLattice;

class LatticeReplyAcquisitionTest {

	@Test
	void receiverDialled() throws Exception {
		acquiresMissingBranch(true);
	}

	@Test
	void senderDialled() throws Exception {
		acquiresMissingBranch(false);
	}

	private void acquiresMissingBranch(boolean receiverDials) throws Exception {
		File file;
		var branch=Blob.wrap(new byte[400]);
		var senderKey=AKeyPair.generate();
		var receiverKey=AKeyPair.generate();
		var incoming=new CompletableFuture<AConnection>();
		try (var receiverStore=EtchStore.createTemp("reply-acquisition"); var senderStore=new MemoryStore();
				var receiver=new NodeServer<>(RelayHost.open(SetLattice.create(),receiverStore),NodeConfig.port(-1));
				var sender=new NodeServer<>(RelayHost.open(SetLattice.create(),senderStore),NodeConfig.port(-1));
				var listener=new LatticeListener(NodeConfig.localNetwork())) {
			file=receiverStore.getFile();
			var senderGroup=new LatticePropagator(senderStore,LatticePropagatorConfig.create());
			var receiverGroup=new LatticePropagator(receiverStore,LatticePropagatorConfig.create());
			senderGroup.setTransportKeyPair(senderKey);
			receiverGroup.setTransportKeyPair(receiverKey);
			sender.addPropagator(senderGroup);
			receiver.addPropagator(receiverGroup);
			var listening=receiverDials ? senderGroup : receiverGroup;
			var dialling=receiverDials ? receiverGroup : senderGroup;
			var listenerKey=(receiverDials ? senderKey : receiverKey).getAccountKey();
			var diallerKey=(receiverDials ? receiverKey : senderKey).getAccountKey();
			listener.registerPropagator(listening);
			listener.setSelector(c -> { incoming.complete(c); return listening; });
			sender.launch();
			receiver.launch();
			listener.launch();
			listening.getConnectionManager().addPeer(diallerKey);
			var client=dialling.getConnectionManager().connectPeer(listenerKey,
				new InetSocketAddress("localhost",listener.getPort())).get(5,TimeUnit.SECONDS);
			var inbound=incoming.get(5,TimeUnit.SECONDS);
			listening.authenticateInboundRoute(inbound,diallerKey);
			listening.getConnectionManager().whenInboundConnectionUpgraded(diallerKey).get(5,TimeUnit.SECONDS);

			// Send only the top cell. Its one non-embedded branch exists solely on the sender.
			var value=Cells.persist(Sets.of(branch),senderStore);
			assertNull(receiverStore.refForHash(branch.getHash()));
			var id=CVMLong.create(-1); // outside the connection's generated request IDs
			var payload=Vectors.create(MessageTag.LATTICE_VALUE,id,Vectors.empty(),value);
			var partial=Message.create(MessageType.LATTICE_VALUE,payload,payload.getEncoding());
			Result result=receiverDials
				? inbound.sendRequest(partial,5000).get(5,TimeUnit.SECONDS).getPayload(senderStore)
				: client.message(partial).get(5,TimeUnit.SECONDS);
			assertFalse(result.isError(),result::toString);
			assertEquals(value,receiver.getLocalValue());
			receiver.getHost().checkpoint();
		}

		try (var reopened=EtchStore.create(file);
				var acquisition=Acquiror.create(branch.getHash(),reopened,hashes ->
					CompletableFuture.failedFuture(new AssertionError("Unexpected request for a persisted branch")))) {
			assertEquals(Sets.of(branch),reopened.getRootData());
			assertEquals(branch,acquisition.getFuture().get(5,TimeUnit.SECONDS));
		}
	}
}
