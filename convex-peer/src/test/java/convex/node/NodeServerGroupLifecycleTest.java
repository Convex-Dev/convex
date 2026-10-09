package convex.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.core.data.ACell;
import convex.core.data.Vectors;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.message.MessageType;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.lattice.generic.MaxLattice;

/** Removing a policy group must leave the authoritative host and other groups live. */
public class NodeServerGroupLifecycleTest {

	@Test
	public void testRemoveRunningGroupAndRelaunch() throws Exception {
		try (MemoryStore nodeStore=new MemoryStore();
				MemoryStore servingStore=new MemoryStore();
				NodeServer<AInteger> node=new NodeServer<>(MaxLattice.create(),nodeStore)) {
			LatticePropagator removed=group(servingStore);
			LatticePropagator retained=group(nodeStore);
			node.addPropagator(removed);
			node.addPropagator(retained);
			node.getCursor().set(CVMLong.create(10));
			node.launch();
			List<LatticePropagator> before=node.getPropagators();

			assertTrue(node.removePropagator(removed));
			assertFalse(node.removePropagator(removed));
			assertEquals(List.of(retained),node.getPropagators());
			assertEquals(List.of(removed,retained),before,"earlier snapshots remain stable");
			assertEquals(LatticePropagator.State.STOPPED,removed.getStatus().state());
			assertTrue(node.isRunning());
			assertTrue(retained.isRunning());
			assertThrows(IllegalArgumentException.class,() -> node.pull(removed));
			assertThrows(IllegalArgumentException.class,() -> node.removePropagator(null));

			CompletableFuture<ACell> announced=retained.nextAnnounce();
			node.getCursor().set(CVMLong.create(20));
			node.getCursor().sync();
			assertEquals(CVMLong.create(20),announced.get(5,TimeUnit.SECONDS));
			assertEquals(CVMLong.create(20),nodeStore.getRootData());
			assertEquals(CVMLong.create(10),removed.getLastAnnouncedValue());
			assertEquals(CVMLong.create(10),
				servingStore.refForHash(CVMLong.create(10).getHash()).getValue(),
				"removal must not close or clear the caller's serving store");

			node.close();
			node.launch();
			assertFalse(removed.isRunning(),"removed groups must not restart with the node");
			assertTrue(retained.isRunning());
			assertTrue(node.removePropagator(retained));
			assertTrue(node.getPropagators().isEmpty());
			assertNull(node.getPropagator());
			node.getCursor().set(CVMLong.create(30));
			node.getCursor().sync();
			assertEquals(CVMLong.create(30),nodeStore.getRootData());
		}
	}

	@Test
	public void testRemoveBeforeLaunchDoesNotCloseUnattachedGroup() throws Exception {
		try (MemoryStore store=new MemoryStore();
				NodeServer<AInteger> node=new NodeServer<>(MaxLattice.create(),store)) {
			LatticePropagator attached=group(store);
			try (LatticePropagator unattached=group(store)) {
				node.addPropagator(attached);
				assertFalse(node.removePropagator(unattached));
				assertEquals(LatticePropagator.State.NEW,unattached.getStatus().state());
				assertTrue(node.removePropagator(attached));
				assertEquals(LatticePropagator.State.STOPPED,attached.getStatus().state());
				node.launch();
				assertTrue(node.isRunning());
				assertFalse(attached.isRunning());
			}
		}
	}

	@Test
	public void testRemovalDrainsAcceptedIngress() throws Exception {
		try (MemoryStore store=new MemoryStore();
				NodeServer<AInteger> node=new NodeServer<>(MaxLattice.create(),store)) {
			LatticePropagator removed=group(store);
			LatticePropagator retained=group(store);
			node.addPropagator(removed);
			node.addPropagator(retained);
			node.launch();
			CompletableFuture<ACell> announced=retained.nextAnnounce();
			Message update=Message.create(MessageType.LATTICE_VALUE,
				Vectors.create(MessageTag.LATTICE_VALUE,Vectors.empty(),CVMLong.create(42)));
			assertNull(removed.deliverIncomingMessage(update));

			assertTrue(node.removePropagator(removed));
			assertEquals(CVMLong.create(42),node.getLocalValue());
			assertEquals(CVMLong.create(42),store.getRootData());
			assertEquals(CVMLong.create(42),announced.get(5,TimeUnit.SECONDS));
		}
	}

	@Test
	public void testRemovalRacesWithPublication() throws Exception {
		CountDownLatch entered=new CountDownLatch(1);
		CountDownLatch release=new CountDownLatch(1);
		try (MemoryStore store=new MemoryStore();
				NodeServer<AInteger> node=new NodeServer<>(MaxLattice.create(),store)) {
			LatticePropagator removed=new LatticePropagator(store,MaxLattice.create(),value -> value) {
				@Override public void triggerBroadcast(ACell value) {
					entered.countDown();
					try {
						assertTrue(release.await(5,TimeUnit.SECONDS));
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new AssertionError(e);
					}
					super.triggerBroadcast(value);
				}
			};
			LatticePropagator retained=group(store);
			node.addPropagator(removed);
			node.addPropagator(retained);
			node.launch();
			CompletableFuture<ACell> announced=retained.nextAnnounce();
			node.getCursor().set(CVMLong.create(42));
			CompletableFuture<Void> publication=CompletableFuture.runAsync(() -> node.getCursor().sync());
			try {
				assertTrue(entered.await(5,TimeUnit.SECONDS));
				assertTrue(node.removePropagator(removed));
				assertFalse(removed.isRunning());
			} finally {
				release.countDown();
			}
			publication.get(5,TimeUnit.SECONDS);
			assertEquals(CVMLong.create(42),announced.get(5,TimeUnit.SECONDS));
			assertEquals(CVMLong.ZERO,removed.getLastAnnouncedValue());
			assertFalse(removed.getStatus().hasFailures());
		}
	}

	@Test
	public void testRemovalFailureIsIsolated() throws Exception {
		IllegalStateException failure=new IllegalStateException("simulated group shutdown failure");
		try (MemoryStore store=new MemoryStore();
				NodeServer<AInteger> node=new NodeServer<>(MaxLattice.create(),store)) {
			LatticePropagator broken=new LatticePropagator(store,MaxLattice.create(),value -> value) {
				@Override public void close() {
					super.close();
					throw failure;
				}
			};
			LatticePropagator retained=group(store);
			node.addPropagator(broken);
			node.addPropagator(retained);
			node.launch();
			assertTrue(node.removePropagator(broken));
			assertSame(failure,broken.getStatus().lastFailure().cause());
			assertEquals(List.of(retained),node.getPropagators());
			CompletableFuture<ACell> announced=retained.nextAnnounce();
			node.getCursor().set(CVMLong.create(42));
			node.getCursor().sync();
			assertEquals(CVMLong.create(42),announced.get(5,TimeUnit.SECONDS));
		}
	}

	@Test
	public void testLatePullCannotMergeAfterRemoval() throws Exception {
		CompletableFuture<ACell> acquired=new CompletableFuture<>();
		try (MemoryStore store=new MemoryStore();
				NodeServer<AInteger> node=new NodeServer<>(MaxLattice.create(),store)) {
			LatticePropagator group=new LatticePropagator(store,MaxLattice.create(),value -> value) {
				@Override public CompletableFuture<ACell> pull(Convex peer) {
					return acquired;
				}
				@Override public CompletableFuture<ACell> pullPath(Convex peer,boolean background,ACell... path) {
					return acquired;
				}
			};
			node.addPropagator(group);
			node.launch();
			CompletableFuture<AInteger> rootPull=node.pull(group,null);
			CompletableFuture<ACell> pathPull=node.pullPath(group,null);
			assertTrue(node.removePropagator(group));
			acquired.complete(CVMLong.create(42));
			assertTrue(assertThrows(ExecutionException.class,
				() -> rootPull.get(5,TimeUnit.SECONDS)).getCause() instanceof IllegalArgumentException);
			assertTrue(assertThrows(ExecutionException.class,
				() -> pathPull.get(5,TimeUnit.SECONDS)).getCause() instanceof IllegalArgumentException);
			assertEquals(CVMLong.ZERO,node.getLocalValue());
			assertEquals(CVMLong.ZERO,store.getRootData());
		}
	}

	private static LatticePropagator group(AStore store) {
		return new LatticePropagator(store,MaxLattice.create(),value -> value);
	}
}
