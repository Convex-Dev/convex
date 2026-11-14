package convex.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import convex.api.ConvexRemote;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.message.Message;
import convex.core.message.AConnection;
import convex.core.message.MessageTag;
import convex.core.message.MessageType;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.lattice.generic.MaxLattice;
import convex.net.IncomingHandler;

/** Tests application-owned inbound transport composition. */
public class LatticeListenerTest {

	@Test
	public void testConfigurationAndLifecycle() throws Exception {
		try (AStore store=new MemoryStore();
				NodeServer<convex.core.data.prim.AInteger> node=
					new NodeServer<>(RelayHost.open(MaxLattice.create(),store));
				LatticeListener listener=new LatticeListener(NodeConfig.port(0))) {
			LatticePropagator group=new LatticePropagator(store);
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
			listener.registerPropagator(group);
			listener.setSelector(connection -> group);
			int port=listener.getPort();
			listener.launch();
			assertEquals(port,listener.getPort(),"launching an open listener does nothing");

			listener.close();
			assertFalse(listener.isRunning());
			assertNull(listener.getHostAddress());
			assertTrue(node.isRunning());
			assertThrows(IllegalStateException.class,listener::launch,"closing is final");
			assertFalse(listener.isRunning());
			listener.close();
		}
	}

	/** A listener closed before it was launched never opens. */
	@Test
	public void testListenerClosedBeforeLaunchNeverOpens() throws Exception {
		try (LatticeListener listener=new LatticeListener(NodeConfig.port(0))) {
			listener.close();
			assertThrows(IllegalStateException.class,listener::launch);
			assertFalse(listener.isRunning());
			assertNull(listener.getHostAddress());
		}
	}

	@Test
	public void testSharedListenerRoutesConnectionsToRegisteredGroups() throws Exception {
		try (AStore nodeStore=new MemoryStore();
				AStore firstStore=new MemoryStore();
				AStore secondStore=new MemoryStore();
				NodeServer<convex.core.data.prim.AInteger> node=
					new NodeServer<>(RelayHost.open(MaxLattice.create(),nodeStore));
				LatticeListener listener=new LatticeListener(NodeConfig.port(0))) {
			LatticePropagator first=new LatticePropagator(firstStore);
			first.setPublicationFilter(ignored -> CVMLong.create(11));
			LatticePropagator second=new LatticePropagator(secondStore);
			second.setPublicationFilter(ignored -> CVMLong.create(22));
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
					new NodeServer<>(RelayHost.open(MaxLattice.create(),nodeStore));
				LatticeListener first=new LatticeListener(NodeConfig.port(0));
				LatticeListener second=new LatticeListener(NodeConfig.port(0))) {
			LatticePropagator group=new LatticePropagator(nodeStore);
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
				assertTrue(first.unregisterPropagator(group));
				assertTrue(group.isRunning());
				assertEquals(CVMLong.ZERO,queryRoot(secondClient,6).getValue(),
					"unregistering one listener must not affect the group's other listener");
			}
		}
	}

	@Test
	public void testSelectorFailureDoesNotAffectNodeOrListener() throws Exception {
		try (AStore store=new MemoryStore();
				NodeServer<convex.core.data.prim.AInteger> node=
					new NodeServer<>(RelayHost.open(MaxLattice.create(),store));
				LatticeListener listener=new LatticeListener(NodeConfig.port(0))) {
			LatticePropagator group=new LatticePropagator(store);
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
				assertEquals(Strings.create("Inbound propagation policy failed"),denied.getValue());
			}
			assertTrue(node.isRunning());
			assertTrue(listener.isRunning());
		}
	}

	@Test
	public void testSelectorOutOfMemoryDeniesOnlyThatConnection() throws Exception {
		try (AStore store=new MemoryStore();
				NodeServer<convex.core.data.prim.AInteger> node=
					new NodeServer<>(RelayHost.open(MaxLattice.create(),store));
				LatticeListener listener=new LatticeListener(NodeConfig.port(0))) {
			LatticePropagator group=new LatticePropagator(store);
			node.addPropagator(group);
			listener.registerPropagator(group);
			listener.setSelector(connection -> {
				throw new OutOfMemoryError("simulated");
			});
			node.launch();
			listener.launch();

			try (ConvexRemote client=ConvexRemote.connect(listener.getHostAddress())) {
				Result denied=client.message(Message.createPing(6)).get(5,TimeUnit.SECONDS);
				assertEquals(ErrorCodes.TRUST,denied.getErrorCode());
			}
			listener.setSelector(connection -> group);
			try (ConvexRemote client=ConvexRemote.connect(listener.getHostAddress())) {
				assertEquals(CVMLong.ZERO,queryRoot(client,7).getValue());
			}
			assertTrue(node.isRunning());
			assertTrue(listener.isRunning());
		}
	}

	/** A group that throws a chosen Error once when accepting, delivering to or releasing a connection. */
	private static final class FaultingGroup extends LatticePropagator {
		volatile Error onAccept;
		volatile Error onDeliver;
		volatile Error onRemove;

		FaultingGroup(AStore store) {
			super(store);
		}

		@Override public IncomingHandler accept(AConnection connection) {
			Error accepting=onAccept;
			if (accepting!=null) {
				onAccept=null;
				throw accepting;
			}
			IncomingHandler inner=super.accept(connection);
			return new IncomingHandler() {
				@Override public Predicate<Message> handle(Message message) {
					Error delivering=onDeliver;
					if (delivering!=null) {
						onDeliver=null;
						throw delivering;
					}
					return inner.handle(message);
				}
				@Override public void close() {
					inner.close();
				}
			};
		}

		@Override public void removeInboundConnection(AConnection connection) {
			Error removing=onRemove;
			if (removing!=null) {
				onRemove=null;
				throw removing;
			}
			super.removeInboundConnection(connection);
		}
	}

	/** Running out of memory accepting or delivering to one connection fails that connection alone. */
	@Test
	public void testGroupOutOfMemoryFailsOnlyThatConnection() throws Exception {
		try (AStore store=new MemoryStore();
				NodeServer<convex.core.data.prim.AInteger> node=
					new NodeServer<>(RelayHost.open(MaxLattice.create(),store));
				LatticeListener listener=new LatticeListener(NodeConfig.port(0))) {
			FaultingGroup group=new FaultingGroup(store);
			node.addPropagator(group);
			listener.registerPropagator(group);
			listener.setSelector(connection -> group);
			node.launch();
			listener.launch();

			group.onAccept=new OutOfMemoryError("simulated accept");
			CompletableFuture<LatticePropagator.Failure> accepting=group.nextFailure();
			try (ConvexRemote client=ConvexRemote.connect(listener.getHostAddress())) {
				client.message(Message.createPing(1));
				assertTrue(accepting.get(5,TimeUnit.SECONDS).cause() instanceof OutOfMemoryError);
			}
			group.onDeliver=new OutOfMemoryError("simulated delivery");
			CompletableFuture<LatticePropagator.Failure> delivering=group.nextFailure();
			try (ConvexRemote client=ConvexRemote.connect(listener.getHostAddress())) {
				client.message(Message.createPing(2));
				assertTrue(delivering.get(5,TimeUnit.SECONDS).cause() instanceof OutOfMemoryError);
			}
			try (ConvexRemote client=ConvexRemote.connect(listener.getHostAddress())) {
				assertEquals(CVMLong.ZERO,queryRoot(client,3).getValue());
			}
			assertTrue(node.isRunning());
			assertTrue(listener.isRunning());
		}
	}

	/** Running out of memory releasing one connection does not stop the listener closing. */
	@Test
	public void testCloseCompletesDespiteOutOfMemoryReleasingAConnection() throws Exception {
		try (AStore store=new MemoryStore();
				NodeServer<convex.core.data.prim.AInteger> node=
					new NodeServer<>(RelayHost.open(MaxLattice.create(),store))) {
			FaultingGroup group=new FaultingGroup(store);
			node.addPropagator(group);
			LatticeListener listener=new LatticeListener(NodeConfig.port(0));
			listener.registerPropagator(group);
			listener.setSelector(connection -> group);
			node.launch();
			listener.launch();
			try (ConvexRemote first=ConvexRemote.connect(listener.getHostAddress());
					ConvexRemote second=ConvexRemote.connect(listener.getHostAddress())) {
				assertEquals(CVMLong.ZERO,queryRoot(first,1).getValue());
				assertEquals(CVMLong.ZERO,queryRoot(second,2).getValue());
				group.onRemove=new OutOfMemoryError("simulated release");
				listener.close();
				assertFalse(listener.isRunning());
				assertNull(group.onRemove,"releasing a connection ran out of memory");
			}
			assertTrue(node.isRunning());
		}
	}

	private static Result queryRoot(ConvexRemote client,long id) throws Exception {
		AVector<?> payload=Vectors.create(
			MessageTag.LATTICE_QUERY,CVMLong.create(id),Vectors.empty());
		return client.message(Message.create(MessageType.LATTICE_QUERY,payload))
			.get(5,TimeUnit.SECONDS);
	}

	@Test
	public void testUnregisterClosesOnlySelectedGroupsConnections() throws Exception {
		try (AStore store=new MemoryStore();
				NodeServer<?> node=new NodeServer<>(RelayHost.open(MaxLattice.create(),store));
				LatticeListener listener=new LatticeListener(NodeConfig.port(0))) {
			LatticePropagator first=new LatticePropagator(store);
			LatticePropagator second=new LatticePropagator(store);
			node.addPropagator(first);
			node.addPropagator(second);
			listener.registerPropagator(first);
			listener.registerPropagator(second);
			AtomicReference<LatticePropagator> selected=new AtomicReference<>(first);
			AtomicReference<AConnection> assigned=new AtomicReference<>();
			listener.setSelector(connection -> {
				assigned.set(connection);
				return selected.get();
			});
			node.launch();
			listener.launch();
			try (ConvexRemote firstClient=ConvexRemote.connect(listener.getHostAddress());
					ConvexRemote secondClient=ConvexRemote.connect(listener.getHostAddress())) {
				assertFalse(queryRoot(firstClient,10).isError());
				AConnection firstSocket=assigned.get();
				selected.set(second);
				assertFalse(queryRoot(secondClient,11).isError());
				AConnection secondSocket=assigned.get();
				assertTrue(listener.unregisterPropagator(first));
				assertFalse(listener.unregisterPropagator(first));
				assertTrue(firstSocket.isClosed());
				assertFalse(secondSocket.isClosed());
				assertTrue(first.isRunning(),"unregistering a transport does not stop its group");
				assertFalse(queryRoot(secondClient,12).isError());
				assertTrue(listener.isRunning());
				selected.set(first);
				try (ConvexRemote denied=ConvexRemote.connect(listener.getHostAddress())) {
					assertEquals(ErrorCodes.TRUST,queryRoot(denied,13).getErrorCode());
				}
				assertTrue(node.removePropagator(first));
				assertFalse(queryRoot(secondClient,14).isError());
			}
		}
	}

	@Test
	public void testUnregisterDuringSelectionCannotAssignRevokedGroup() throws Exception {
		CountDownLatch entered=new CountDownLatch(1);
		CountDownLatch release=new CountDownLatch(1);
		try (AStore store=new MemoryStore();
				NodeServer<?> node=new NodeServer<>(RelayHost.open(MaxLattice.create(),store));
				LatticeListener listener=new LatticeListener(NodeConfig.port(0))) {
			LatticePropagator group=new LatticePropagator(store);
			node.addPropagator(group);
			listener.registerPropagator(group);
			listener.setSelector(connection -> {
				entered.countDown();
				try {
					assertTrue(release.await(5,TimeUnit.SECONDS));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new AssertionError(e);
				}
				return group;
			});
			node.launch();
			listener.launch();
			try (ConvexRemote client=ConvexRemote.connect(listener.getHostAddress())) {
				CompletableFuture<Result> result=client.message(Message.createPing(15));
				try {
					assertTrue(entered.await(5,TimeUnit.SECONDS));
					assertTrue(listener.unregisterPropagator(group));
				} finally {
					release.countDown();
				}
				assertEquals(ErrorCodes.TRUST,result.get(5,TimeUnit.SECONDS).getErrorCode());
				assertEquals(0,group.getInboundStats().connections());
			}
		}
	}

	@Test
	public void testRemoveGroupClosesInboundSocketsOnEveryListener() throws Exception {
		try (AStore store=new MemoryStore();
				NodeServer<?> node=new NodeServer<>(RelayHost.open(MaxLattice.create(),store));
				LatticeListener first=new LatticeListener(NodeConfig.port(0));
				LatticeListener second=new LatticeListener(NodeConfig.port(0))) {
			LatticePropagator group=new LatticePropagator(store);
			node.addPropagator(group);
			first.registerPropagator(group);
			second.registerPropagator(group);
			AtomicReference<AConnection> firstSocket=new AtomicReference<>();
			AtomicReference<AConnection> secondSocket=new AtomicReference<>();
			first.setSelector(connection -> { firstSocket.set(connection); return group; });
			second.setSelector(connection -> { secondSocket.set(connection); return group; });
			node.launch();
			first.launch();
			second.launch();
			try (ConvexRemote a=ConvexRemote.connect(first.getHostAddress());
					ConvexRemote b=ConvexRemote.connect(second.getHostAddress())) {
				assertFalse(queryRoot(a,16).isError());
				assertFalse(queryRoot(b,17).isError());
				assertTrue(node.removePropagator(group));
				assertTrue(firstSocket.get().isClosed());
				assertTrue(secondSocket.get().isClosed());
				assertTrue(first.isRunning());
				assertTrue(second.isRunning());
				assertTrue(node.isRunning());
				assertThrows(IllegalStateException.class,
					() -> group.attachInboundConnection(firstSocket.get()));
			}
		}
	}
}
