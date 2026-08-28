package convex.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.AVector;
import convex.core.data.ASet;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Ref;
import convex.core.data.Sets;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.etch.EtchStore;
import convex.lattice.ALattice;
import convex.lattice.Lattice;
import convex.lattice.LatticeContext;
import convex.lattice.generic.LWWLattice;
import convex.lattice.generic.SetLattice;

/**
 * Tests for automatic lattice propagation using LatticePropagator.
 */
public class LatticePropagatorTest {

	private ALattice<?> lattice;
	private NodeServer<?> server1;
	private NodeServer<?> server2;
	private LatticePropagator propagator1;
	private LatticePropagator propagator2;
	private AStore store1;
	private AStore store2;

	@BeforeEach
	public void setUp() throws IOException, InterruptedException {
		// Use the base lattice
		lattice = Lattice.ROOT;

		// Create two NodeServers
		store1 = new MemoryStore();
		store2 = new MemoryStore();

		// Port 0 = OS-assigned free ports, avoiding bind collisions on busy CI runners.
		// Peer connections below use getHostAddress(), which reflects the actual ports.
		server1 = new NodeServer<>(lattice, store1, NodeConfig.port(0));
		server2 = new NodeServer<>(lattice, store2, NodeConfig.port(0));
		AKeyPair nodeKey1=AKeyPair.generate();
		AKeyPair nodeKey2=AKeyPair.generate();
		server1.setMergeContext(LatticeContext.create(null,nodeKey1));
		server2.setMergeContext(LatticeContext.create(null,nodeKey2));
		propagator1=new LatticePropagator(store1,lattice,value -> value,
			LatticePropagatorConfig.create());
		propagator2=new LatticePropagator(store2,lattice,value -> value,
			LatticePropagatorConfig.create());
		propagator1.setMergeContext(LatticeContext.create(null,nodeKey1));
		propagator2.setMergeContext(LatticeContext.create(null,nodeKey2));
		propagator1.setTransportKeyPair(nodeKey1);
		propagator2.setTransportKeyPair(nodeKey2);
		server1.addPropagator(propagator1);
		server2.addPropagator(propagator2);
		// These propagation unit tests model routes after authentication. The
		// challenge/response and inbound-upgrade paths are exercised separately by
		// NodeServerTest and P2PSocialSyncTest; assigning the proven remote key here
		// ensures DATA-ahead is never tested over an unverified connection.
		server1.setInboundPropagatorSelector(connection -> {
			connection.setTrustedKey(nodeKey2.getAccountKey());
			return propagator1;
		});
		server2.setInboundPropagatorSelector(connection -> {
			connection.setTrustedKey(nodeKey1.getAccountKey());
			return propagator2;
		});

		// Launch both servers
		server1.launch();
		server2.launch();

		// Establish bidirectional peer connections
		// Server1 -> Server2 (so server1 can broadcast to server2)
		// Server2 -> Server1 (so server2 can broadcast to server1)
		try {
			InetSocketAddress server1Address = server1.getHostAddress();
			InetSocketAddress server2Address = server2.getHostAddress();

			Convex peer1to2 = ConvexRemote.connect(server2Address);
			propagator1.addPeer(nodeKey2.getAccountKey(),peer1to2)
				.get(5,TimeUnit.SECONDS);

			Convex peer2to1 = ConvexRemote.connect(server1Address);
			propagator2.addPeer(nodeKey1.getAccountKey(),peer2to1)
				.get(5,TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new RuntimeException("Failed to establish peer connections", e);
		}
	}

	@AfterEach
	public void tearDown() throws IOException {
		if (server1 != null) {
			server1.close();
		}
		if (server2 != null) {
			server2.close();
		}
		if (store1 != null) {
			store1.close();
		}
		if (store2 != null) {
			store2.close();
		}
	}

	/**
	 * Tests that the propagator is automatically started when NodeServer launches.
	 */
	@Test
	public void testPropagatorAutoStart() {
		assertSame(propagator1,server1.getPropagators().get(0),
			"Node should retain the application-supplied propagator");
		assertTrue(propagator1.isRunning(), "Propagator should be running");

		assertSame(propagator2,server2.getPropagators().get(0),
			"Node should retain the application-supplied propagator");
		assertTrue(propagator2.isRunning(), "Propagator should be running");
	}

	@Test
	public void testAddPeerReportsIdentityFailure() throws Exception {
		AccountKey wrongKey=AKeyPair.generate().getAccountKey();
		Convex peer=ConvexRemote.connect(server2.getHostAddress());

		assertThrows(ExecutionException.class,
			()->propagator1.addPeer(wrongKey,peer).get(5,TimeUnit.SECONDS));
		assertFalse(propagator1.getConnectionManager().isConnected(wrongKey));
	}

	/**
	 * Tests that automatic propagation broadcasts updates to connected peers.
	 *
	 * This test verifies that:
	 * 1. The propagator detects value changes
	 * 2. The propagator broadcasts to connected peers
	 * 3. Broadcasts are sent automatically without manual intervention
	 * 4. Broadcast value is successfully obtained by remote peer
	 */
	@Test
	public void testAutomaticPropagation() throws Exception {
		// Get the :data keyword
		Keyword dataKeyword = Keyword.intern("data");

		// Create a test value
		ACell testValue = CVMLong.create(99999);
		Hash valueHash = Hash.get(testValue);

		// Update server2's lattice value at [:data hash]
		@SuppressWarnings("unchecked")
		Index<Hash, ACell> dataIndex = (Index<Hash, ACell>) server2.getCursor().get(dataKeyword);
		if (dataIndex == null) {
			@SuppressWarnings("unchecked")
			Index<Hash, ACell> emptyIndex = (Index<Hash, ACell>) Index.EMPTY;
			dataIndex = emptyIndex;
		}
		Index<Hash, ACell> updatedDataIndex = dataIndex.assoc(valueHash, testValue);
		server2.getCursor().assoc(dataKeyword, updatedDataIndex);
		// Synchronous commit persists the authoritative node root.
		server2.getCursor().sync();

		// Pull from server2 into server1
		assertTrue(server1.pull(propagator1), "Pull should complete successfully");

		// Verify server1 received the value from server2
		assertEquals(testValue, RT.getIn(server1.getLocalValue(), dataKeyword, valueHash),
			"Server1 should have received the value broadcast from server2");
	}

	/** Delta broadcast retains the protocol envelope and drives a real push merge. */
	@Test
	public void testDeltaPushUsesLatticeValueEnvelope() throws Exception {
		Keyword dataKeyword = Keyword.intern("data");
		ACell expected = CVMLong.create(4242);
		Hash expectedHash = expected.getHash();
		@SuppressWarnings("unchecked")
		Index<Hash, ACell> values = (Index<Hash, ACell>) Index.EMPTY;
		values = values.assoc(expectedHash, expected);
		long acceptedBefore=propagator2.getInboundStats().mergesAccepted();
		CompletableFuture<ACell> received=propagator2.nextAnnounce();
		server1.getCursor().assoc(dataKeyword, values);
		server1.getCursor().sync();

		received.get(5,TimeUnit.SECONDS);
		assertEquals(1L, propagator1.getBroadcastCount(),
			"source should send one delta broadcast");
		LatticePropagator.InboundStats inbound = propagator2.getInboundStats();
		assertTrue(inbound.mergesAccepted()>=acceptedBefore+1,
			"receiver should accept the pushed lattice merge: " + inbound);
		ACell merged = server2.getLocalValue();
		assertEquals(expected, RT.getIn(merged, dataKeyword, expectedHash),
			"receiver should decode the LATTICE_VALUE tag/path before merging the delta");
	}

	/** Oversized novelty is staged as bounded DATA messages before one root merge. */
	@Test
	public void testOversizedDeltaIsChunkedWithoutBlockingOtherMessages() throws Exception {
		Keyword dataKeyword=Keyword.intern("data");
		@SuppressWarnings("unchecked")
		Index<Hash,ACell> values=(Index<Hash,ACell>)Index.EMPTY;
		for (int i=0; i<6; i++) {
			Blob value=Blobs.createRandom(300);
			values=values.assoc(value.getHash(),value);
		}

		propagator1.setMaxDeltaMessageSize(700);
		long acceptedBefore=propagator2.getInboundStats().mergesAccepted();
		CompletableFuture<ACell> received=propagator2.nextAnnounce();
		server1.getCursor().assoc(dataKeyword,values);
		server1.getCursor().sync();

		received.get(5,TimeUnit.SECONDS);
		assertEquals(values,RT.getIn(server2.getLocalValue(),dataKeyword));
		LatticePropagator.InboundStats inbound=propagator2.getInboundStats();
		assertTrue(inbound.messagesReceived()>1,
			"chunked delta should arrive as DATA batches plus one root: "+inbound);
		assertTrue(inbound.mergesAccepted()>=acceptedBefore+1);
	}

	/** A delta encoding failure must not hide the newly announced root from recovery. */
	@Test
	public void testFailedDeltaStillAdvancesRootSyncAndAnnounceFuture() throws Exception {
		Blob value=Blobs.createRandom(400);
		server1.getCursor().assoc(Keyword.intern("failed-delta"),value);
		ACell expected=server1.getLocalValue();

		// Use an isolated publisher so node fan-out cannot race this synchronous API
		// assertion. Borrowing a live route is safe: the one-byte limit rejects the
		// root before any message is queued on it.
		try (MemoryStore isolatedStore=new MemoryStore()) {
			LatticeConnectionManager manager=new LatticeConnectionManager(isolatedStore);
			Convex livePeer=propagator1.getPeers().iterator().next();
			manager.addPeer(livePeer.getVerifiedPeer(),livePeer).get(5,TimeUnit.SECONDS);
			LatticePropagator isolated=new LatticePropagator(
				isolatedStore,manager,lattice,candidate -> candidate);
			isolated.setMaxDeltaMessageSize(1);
			CompletableFuture<ACell> announced=isolated.nextAnnounce();
			ACell published=isolated.processSnapshot(expected);

			assertEquals(published,announced.get(5,TimeUnit.SECONDS));
			assertEquals(published.getHash(),
				rootSyncValueHash(isolated.createRootSyncMessage()));
		}
	}

	/** Consecutive explicit syncs may coalesce, but the latest value must converge. */
	@Test
	public void testConsecutiveSyncsEachBroadcast() throws Exception {
		Keyword dataKeyword = Keyword.intern("data");
		ACell first = CVMLong.create(4301);
		ACell second = CVMLong.create(4302);
		@SuppressWarnings("unchecked")
		Index<Hash, ACell> values = (Index<Hash, ACell>) Index.EMPTY;
		CompletableFuture<ACell> firstMerge=propagator2.nextAnnounce();
		CompletableFuture<ACell> secondMerge=firstMerge.thenCompose(
			value -> second.equals(RT.getIn(value,dataKeyword,second.getHash()))
				? CompletableFuture.completedFuture(value)
				: propagator2.nextAnnounce());

		server1.getCursor().assoc(dataKeyword, values.assoc(first.getHash(), first));
		server1.getCursor().sync();
		server1.getCursor().assoc(dataKeyword,
			values.assoc(first.getHash(), first).assoc(second.getHash(), second));
		server1.getCursor().sync();

		secondMerge.get(5,TimeUnit.SECONDS);
		assertTrue(propagator1.getBroadcastCount()>=1,
			"the coalesced latest value must be broadcast");
		assertEquals(second, RT.getIn(server2.getLocalValue(), dataKeyword, second.getHash()));
	}

	/** A queued value must not be advertised until announce has populated the store. */
	@Test
	public void testRootSyncUsesLastAnnouncedValue() throws Exception {
		Blob announced = Blobs.createRandom(400);
		Blob pending = Blobs.createRandom(400);
		try (BlockingAnnounceStore store = new BlockingAnnounceStore(pending.getHash())) {
			LatticePropagator propagator = new LatticePropagator(store);
			try {
				propagator.processSnapshot(announced);
				propagator.start();
				CompletableFuture<ACell> nextAnnounce = propagator.nextAnnounce();
				propagator.triggerBroadcast(pending);
				assertTrue(store.blocked.await(5, TimeUnit.SECONDS));

				assertEquals(announced.getHash(), rootSyncValueHash(propagator.createRootSyncMessage()),
					"root sync must retain the last store-backed value while announce is incomplete");
				assertNotNull(store.refForHash(announced.getHash()));

				store.release.countDown();
				assertEquals(pending, nextAnnounce.get(5, TimeUnit.SECONDS));
				assertEquals(pending.getHash(), rootSyncValueHash(propagator.createRootSyncMessage()));
				assertNotNull(store.refForHash(pending.getHash()));
			} finally {
				store.release.countDown();
				propagator.close();
			}
		}
	}

	private static Hash rootSyncValueHash(Message message) {
		AVector<?> payload = message.getPayload();
		assertEquals(MessageTag.LATTICE_VALUE,payload.get(0));
		assertEquals(3L,payload.count(),"root sync should be an optimistic push");
		return payload.getRef(2).getHash();
	}

	/** Waits on announce signals until the expected value arrives, without polling. */
	private static CompletableFuture<ACell> nextAnnounceMatching(
			LatticePropagator propagator,Predicate<ACell> predicate) {
		CompletableFuture<ACell> result=new CompletableFuture<>();
		awaitMatching(propagator,predicate,result,16);
		return result;
	}

	private static void awaitMatching(LatticePropagator propagator,
			Predicate<ACell> predicate,CompletableFuture<ACell> result,int remaining) {
		propagator.nextAnnounce().whenComplete((value,error) -> {
			if (error!=null) {
				result.completeExceptionally(error);
			} else {
				boolean matches;
				try {
					matches=predicate.test(value);
				} catch (Throwable predicateFailure) {
					result.completeExceptionally(predicateFailure);
					return;
				}
				if (matches) {
					result.complete(value);
				} else if (remaining<=1) {
					result.completeExceptionally(
						new AssertionError("Expected announcement did not arrive"));
				} else {
					awaitMatching(propagator,predicate,result,remaining-1);
				}
			}
		});
	}

	/** Filtering belongs to the propagator and runs before its store boundary. */
	@Test
	public void testFilterRunsBeforeAnnounce() throws Exception {
		CVMLong visible = CVMLong.create(101);
		Blob hidden = Blobs.createRandom(400);
		SetLattice<ACell> setLattice = SetLattice.create();
		try (MemoryStore publicStore = new MemoryStore()) {
			LatticePropagator propagator = new LatticePropagator(publicStore, setLattice,
				value -> value.exclude(hidden));
			ASet<ACell> full = Sets.of(visible, hidden);

			ACell announced = propagator.processSnapshot(full);

			assertEquals(Sets.of(visible), announced);
			assertSame(announced, propagator.getLastAnnouncedValue());
			assertNull(publicStore.refForHash(hidden.getHash()),
				"a filtered cell must never cross the propagator's store boundary");
		}
	}

	/** Snapshot reconciliation is directional: the propagator's current value is own. */
	@Test
	public void testSnapshotMergePrefersPropagatorOwnValueOnTie() throws Exception {
		LWWLattice<CVMLong> lww = LWWLattice.create(value -> 1L);
		try (MemoryStore viewStore = new MemoryStore()) {
			LatticePropagator propagator = new LatticePropagator(viewStore, lww, value -> value);
			CVMLong own = CVMLong.create(10_001);
			CVMLong other = CVMLong.create(10_002);

			ACell first = propagator.processSnapshot(own);
			ACell second = propagator.processSnapshot(other);

			assertSame(own, first);
			assertSame(first, second,
				"equal-timestamp input must not replace the propagator's existing own value");
			assertSame(first, viewStore.getRootData());
		}
	}

	/** Equivalent foreign-store values retain the propagator's store-local refs. */
	@Test
	@SuppressWarnings("unchecked")
	public void testSnapshotMergeRetainsPropagatorStoreRefIdentity() throws Exception {
		SetLattice<Blob> setLattice = SetLattice.create();
		Blob source = Blobs.createRandom(400);
		try (MemoryStore viewStore = new MemoryStore();
				MemoryStore foreignStore = new MemoryStore()) {
			LatticePropagator propagator = new LatticePropagator(viewStore, setLattice,
				value -> value);
			ASet<Blob> localInput = Sets.of(source);
			ASet<Blob> local = (ASet<Blob>) propagator.processSnapshot(localInput);
			Blob foreignBlob = foreignStore.decode(source.getEncoding());
			ASet<Blob> foreign = convex.core.data.Cells.announce(
				Sets.of(foreignBlob), r -> {}, foreignStore);

			assertEquals(local, foreign);
			assertNotSame(local, foreign);
			assertSame(local, propagator.processSnapshot(foreign),
				"equivalent input from another store must not replace local refs");
			assertSame(local, viewStore.getRootData());
		}
	}

	/** The authoritative node store publishes an explicitly selected local snapshot. */
	@Test
	public void testPrimarySnapshotReplacesEqualTimestampPublication() throws Exception {
		LWWLattice<CVMLong> lww = LWWLattice.create(value -> 1L);
		try (MemoryStore primaryStore = new MemoryStore()) {
			CVMLong old = CVMLong.create(10_011);
			CVMLong authoritative = CVMLong.create(10_012);
			try (NodeServer<CVMLong> node=new NodeServer<>(lww,primaryStore,NodeConfig.port(-1))) {
				node.getCursor().set(old);
				node.launch();
				node.getCursor().set(authoritative);
				node.getCursor().sync();
				assertSame(authoritative,primaryStore.getRootData());
			}
		}
	}

	/** A persistent secondary restores its own subset before primary reconciliation. */
	@Test
	public void testRestoredWorkingViewSurvivesNextSnapshot() throws Exception {
		SetLattice<ACell> setLattice = SetLattice.create();
		CVMLong pending = CVMLong.create(311);
		CVMLong primary = CVMLong.create(312);
		try (EtchStore viewStore = EtchStore.createTemp("propagator-view-restore")) {
			LatticePropagator first = new LatticePropagator(viewStore, setLattice, value -> value);
			first.processSnapshot(Sets.of(pending));
			first.checkpoint();

			LatticePropagator restored = new LatticePropagator(viewStore, setLattice, value -> value);
			assertEquals(Sets.of(pending), restored.restore());
			assertEquals(Sets.of(pending, primary), restored.processSnapshot(Sets.of(primary)),
				"restored propagator state must be own during primary reconciliation");
		}
	}

	/** Filtering is invariant across launch, normal sync fan-out and orderly close. */
	@Test
	public void testNodeLifecycleKeepsSecondaryStoreFiltered() throws Exception {
		SetLattice<ACell> setLattice = SetLattice.create();
		CVMLong initialVisible = CVMLong.create(201);
		CVMLong laterVisible = CVMLong.create(202);
		Blob hidden = Blobs.createRandom(400);
		try (MemoryStore primaryStore = new MemoryStore();
				MemoryStore publicStore = new MemoryStore();
				NodeServer<ASet<ACell>> node = new NodeServer<>(
					setLattice, primaryStore, NodeConfig.port(-1))) {
			LatticePropagator primary = new LatticePropagator(primaryStore, setLattice,
				value -> value);
			LatticePropagator publicView = new LatticePropagator(publicStore, setLattice,
				value -> value.exclude(hidden));
			node.addPropagator(primary);
			node.addPropagator(publicView);
			node.getCursor().set(Sets.of(initialVisible, hidden));

			node.launch();

			assertEquals(Sets.of(initialVisible), publicView.getLastAnnouncedValue(),
				"launch must initialise the secondary's filtered query view");
			assertNull(publicStore.refForHash(hidden.getHash()));

			CompletableFuture<ACell> nextPublicAnnounce = publicView.nextAnnounce();
			node.getCursor().updateAndGet(value -> value.include(laterVisible));
			node.getCursor().sync();
			assertEquals(Sets.of(initialVisible, laterVisible),
				nextPublicAnnounce.get(5, TimeUnit.SECONDS));
			assertNull(publicStore.refForHash(hidden.getHash()));

			node.close();
			assertEquals(Sets.of(initialVisible, laterVisible), publicView.getLastAnnouncedValue(),
				"orderly close must leave the filtered serving view current");
			assertNull(publicStore.refForHash(hidden.getHash()));
		}
	}

	static final class BlockingAnnounceStore extends MemoryStore {
		final Hash blockedHash;
		final CountDownLatch blocked = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);

		BlockingAnnounceStore(Hash blockedHash) {
			this.blockedHash = blockedHash;
		}

		@Override
		public <T extends ACell> Ref<T> storeTopRef(Ref<T> ref, int status,
				Consumer<Ref<ACell>> noveltyHandler) {
			if (blockedHash.equals(ref.getHash())) {
				blocked.countDown();
				try {
					release.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("Interrupted while controlling announce", e);
				}
			}
			return super.storeTopRef(ref, status, noveltyHandler);
		}
	}

	/**
	 * Regression test for the shutdown durability race: triggerAndClose sets
	 * running=false before offering the final value, so the propagation loop
	 * can observe running==false with an empty queue and exit without ever
	 * consuming the value — silently losing the last writes on a clean
	 * shutdown (seen as an intermittent NodeServerPersistenceTest failure on
	 * CI).
	 *
	 * <p>The window is a few instructions wide and cannot be hit reliably by
	 * brute force, so this test constructs the post-race state directly (loop
	 * already exited, final value offered afterwards) and asserts the contract:
	 * the value must still be processed before triggerAndClose returns, via
	 * the closing thread's post-join drain.
	 */
	@Test
	public void testTriggerAndCloseDrainsAfterLoopExit() throws Exception {
		LatticePropagator propagator = new LatticePropagator(new MemoryStore());
		propagator.start();

		// Force the propagation loop to terminate while triggerAndClose still
		// sees a propagator to shut down (running false, thread field non-null)
		Field runningField = LatticePropagator.class.getDeclaredField("running");
		runningField.setAccessible(true);
		runningField.setBoolean(propagator, false);
		Field threadField = LatticePropagator.class.getDeclaredField("propagationThread");
		threadField.setAccessible(true);
		Thread worker = (Thread) threadField.get(propagator);
		worker.interrupt(); // wake from poll; loop drains (empty queue) and exits
		worker.join(10_000);
		assertFalse(worker.isAlive(), "Propagation loop should have exited");

		// The final value is offered to a queue no thread will ever read;
		// only the closing thread's drain can process it
		ACell finalValue = CVMLong.create(424242);
		propagator.triggerAndClose(finalValue);
		assertEquals(finalValue, propagator.getLastAnnouncedValue(),
			"Final value must be announced even when the loop exited before the offer");
	}

	/**
	 * As above, but with a persistent store: the final value must be durable
	 * (readable as root data) once triggerAndClose returns.
	 */
	@Test
	public void testTriggerAndClosePersistsFinalValue() throws IOException {
		try (EtchStore store = EtchStore.createTemp("propagator-close")) {
			LatticePropagator propagator = new LatticePropagator(store);
			propagator.start();
			ACell finalValue = CVMLong.create(12345);
			propagator.triggerAndClose(finalValue);
			assertEquals(finalValue, store.getRootData(),
				"Final value must be persisted as root data before triggerAndClose returns");
		}
	}

	/**
	 * Tests that multiple updates are successfully propagated to remote peers.
	 */
	@Test
	public void testMultipleUpdates() throws Exception {
		// Perform multiple local updates on server1
		Keyword dataKeyword = Keyword.intern("data");

		for (int i = 0; i < 3; i++) {
			ACell testValue = CVMLong.create(1000 + i);
			Hash valueHash = Hash.get(testValue);

			@SuppressWarnings("unchecked")
			Index<Hash, ACell> dataIndex = (Index<Hash, ACell>) server1.getCursor().get(dataKeyword);
			if (dataIndex == null) {
				@SuppressWarnings("unchecked")
				Index<Hash, ACell> emptyIndex = (Index<Hash, ACell>) Index.EMPTY;
				dataIndex = emptyIndex;
			}
			Index<Hash, ACell> updatedDataIndex = dataIndex.assoc(valueHash, testValue);
			server1.getCursor().assoc(dataKeyword, updatedDataIndex);
			// Synchronous commit persists the authoritative node root.
			server1.getCursor().sync();

			// Pull from server1 into server2
			assertTrue(server2.pull(propagator2),
				"Pull should complete successfully for update " + (i + 1));

			// Verify server2 received this specific value
			assertEquals(testValue, RT.getIn(server2.getLocalValue(), dataKeyword, valueHash),
				"Server2 should have received update " + (i + 1) + " from server1");
		}
	}
}
