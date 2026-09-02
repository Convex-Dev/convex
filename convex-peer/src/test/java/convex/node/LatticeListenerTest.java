package convex.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.api.ConvexRemote;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.message.MessageType;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.lattice.generic.MaxLattice;

/** Tests application-owned inbound transport composition. */
public class LatticeListenerTest {

	@Test
	public void testConfigurationAndLifecycle() throws Exception {
		try (AStore store=new MemoryStore();
				NodeServer<convex.core.data.prim.AInteger> node=
					new NodeServer<>(MaxLattice.create(),store);
				LatticeListener listener=new LatticeListener(NodeConfig.port(0))) {
			LatticePropagator group=new LatticePropagator(
				store,MaxLattice.create(),value -> value);
			node.addPropagator(group);
			listener.registerPropagator(group);
			listener.setSelector(connection -> group);
			assertEquals(0,listener.getPort());
			assertNull(listener.getHostAddress());
			assertFalse(listener.isRunning());

			node.launch();
			listener.launch();
			assertTrue(listener.getPort()>0);
			assertTrue(listener.isRunning());
			assertThrows(IllegalStateException.class,
				() -> listener.registerPropagator(group));
			assertThrows(IllegalStateException.class,
				() -> listener.setSelector(connection -> group));
			assertThrows(IllegalStateException.class,listener::launch);

			listener.close();
			assertFalse(listener.isRunning());
			assertNull(listener.getHostAddress());
			assertTrue(node.isRunning());
		}
	}

	@Test
	public void testSharedListenerRoutesConnectionsToRegisteredGroups() throws Exception {
		try (AStore nodeStore=new MemoryStore();
				AStore firstStore=new MemoryStore();
				AStore secondStore=new MemoryStore();
				NodeServer<convex.core.data.prim.AInteger> node=
					new NodeServer<>(MaxLattice.create(),nodeStore);
				LatticeListener listener=new LatticeListener(NodeConfig.port(0))) {
			LatticePropagator first=new LatticePropagator(
				firstStore,MaxLattice.create(),ignored -> CVMLong.create(11));
			LatticePropagator second=new LatticePropagator(
				secondStore,MaxLattice.create(),ignored -> CVMLong.create(22));
			node.addPropagator(first);
			node.addPropagator(second);
			listener.registerPropagator(first);
			listener.registerPropagator(second);
			AtomicInteger accepted=new AtomicInteger();
			listener.setSelector(connection -> accepted.getAndIncrement()==0 ? first : second);

			node.launch();
			listener.launch();
			try (ConvexRemote firstClient=ConvexRemote.connect(listener.getHostAddress());
					ConvexRemote secondClient=ConvexRemote.connect(listener.getHostAddress())) {
				assertEquals(CVMLong.create(11),queryRoot(firstClient,1).getValue());
				assertEquals(CVMLong.create(11),queryRoot(firstClient,2).getValue(),
					"a physical connection must retain its first assignment");
				assertEquals(CVMLong.create(22),queryRoot(secondClient,3).getValue());
			}

			listener.close();
			assertFalse(listener.isRunning());
			assertTrue(node.isRunning(),
				"closing an application transport must not close authoritative state");
		}
	}

	@Test
	public void testOneGroupMayUseIndependentListeners() throws Exception {
		try (AStore nodeStore=new MemoryStore();
				NodeServer<convex.core.data.prim.AInteger> node=
					new NodeServer<>(MaxLattice.create(),nodeStore);
				LatticeListener first=new LatticeListener(NodeConfig.port(0));
				LatticeListener second=new LatticeListener(NodeConfig.port(0))) {
			LatticePropagator group=new LatticePropagator(
				nodeStore,MaxLattice.create(),value -> value);
			node.addPropagator(group);
			first.registerPropagator(group);
			second.registerPropagator(group);
			first.setSelector(connection -> group);
			second.setSelector(connection -> group);

			node.launch();
			first.launch();
			second.launch();
			assertTrue(first.getPort()>0);
			assertTrue(second.getPort()>0);
			assertFalse(first.getPort().equals(second.getPort()));
			try (ConvexRemote firstClient=ConvexRemote.connect(first.getHostAddress());
					ConvexRemote secondClient=ConvexRemote.connect(second.getHostAddress())) {
				assertEquals(CVMLong.ZERO,queryRoot(firstClient,4).getValue());
				assertEquals(CVMLong.ZERO,queryRoot(secondClient,5).getValue());
			}
		}
	}

	@Test
	public void testSelectorFailureDoesNotAffectNodeOrListener() throws Exception {
		try (AStore store=new MemoryStore();
				NodeServer<convex.core.data.prim.AInteger> node=
					new NodeServer<>(MaxLattice.create(),store);
				LatticeListener listener=new LatticeListener(NodeConfig.port(0))) {
			LatticePropagator group=new LatticePropagator(
				store,MaxLattice.create(),value -> value);
			node.addPropagator(group);
			listener.registerPropagator(group);
			listener.setSelector(connection -> {
				throw new IllegalStateException("broken application policy");
			});
			node.launch();
			listener.launch();

			try (ConvexRemote client=ConvexRemote.connect(listener.getHostAddress())) {
				Result denied=client.message(Message.createPing(6)).get(5,TimeUnit.SECONDS);
				assertEquals(ErrorCodes.TRUST,denied.getErrorCode());
			}
			assertTrue(node.isRunning());
			assertTrue(listener.isRunning());
		}
	}

	private static Result queryRoot(ConvexRemote client,long id) throws Exception {
		AVector<?> payload=Vectors.create(
			MessageTag.LATTICE_QUERY,CVMLong.create(id),Vectors.empty());
		return client.message(Message.create(MessageType.LATTICE_QUERY,payload))
			.get(5,TimeUnit.SECONDS);
	}
}
