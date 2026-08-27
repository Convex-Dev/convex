package convex.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.RefSoft;
import convex.core.exceptions.StoreException;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.etch.Etch;
import convex.etch.EtchStore;
import convex.lattice.Lattice;

/**
 * Integration tests for NodeServer persistence and replication.
 *
 * Tests a primary + backup scenario with EtchStore persistence,
 * verifying broadcast, stop/start, and restore at various points.
 */
@Execution(ExecutionMode.SAME_THREAD)
public class NodeServerPersistenceTest {
	@FunctionalInterface
	private interface RootWriteHook {
		void run() throws IOException;
	}

	/** One reusable Etch file per role; tests reset only the root pointer. */
	private static class HookedEtchStore extends EtchStore {
		private volatile RootWriteHook rootWriteHook;
		private volatile RootWriteHook flushHook;
		private volatile RootWriteHook flushCompleteHook;

		HookedEtchStore(String prefix) throws IOException {
			super(Etch.createTempEtch(prefix));
		}

		void setRootWriteHook(RootWriteHook hook) {
			rootWriteHook = hook;
		}

		void setFlushHook(RootWriteHook hook) {
			flushHook = hook;
		}

		void setFlushCompleteHook(RootWriteHook hook) {
			flushCompleteHook = hook;
		}

		@Override
		public <T extends ACell> convex.core.data.Ref<T> setRootData(T data) throws IOException {
			RootWriteHook hook = rootWriteHook;
			if (hook != null) hook.run();
			return super.setRootData(data);
		}

		@Override
		public void flush() throws IOException {
			RootWriteHook hook = flushHook;
			if (hook != null) hook.run();
			super.flush();
			hook = flushCompleteHook;
			if (hook != null) hook.run();
		}
	}

	private HookedEtchStore sharedPrimaryStore;
	private HookedEtchStore sharedBackupStore;

	private NodeServer<?> primary;
	private NodeServer<?> backup;
	private AStore primaryStore;
	private AStore backupStore;

	/** Attaches the explicit identity-view group used by replication tests. */
	private static LatticePropagator addPropagationGroup(NodeServer<?> node) {
		LatticePropagator propagator=new LatticePropagator(
			node.getStore(),node.getLattice(),value -> value,node.getConfig());
		node.addPropagator(propagator);
		return propagator;
	}

	/** Exposes the configured group on every inbound test connection. */
	private static LatticePropagator serveInbound(NodeServer<?> node) {
		LatticePropagator propagator=addPropagationGroup(node);
		node.setInboundPropagatorSelector(connection -> propagator);
		return propagator;
	}

	/** Returns the one application-owned group expected by a replication fixture. */
	private static LatticePropagator propagationGroup(NodeServer<?> node) {
		assertEquals(1,node.getPropagators().size());
		return node.getPropagators().get(0);
	}

	@BeforeEach
	void createStores() throws IOException {
		sharedPrimaryStore = new HookedEtchStore("node-persistence-primary");
		sharedBackupStore = new HookedEtchStore("node-persistence-backup");
		primaryStore = sharedPrimaryStore;
		backupStore = sharedBackupStore;
	}

	@AfterEach
	public void tearDown() throws IOException {
		// Test hooks must not affect the final snapshot written during close().
		sharedPrimaryStore.setRootWriteHook(null);
		sharedBackupStore.setRootWriteHook(null);
		sharedPrimaryStore.setFlushHook(null);
		sharedBackupStore.setFlushHook(null);
		sharedPrimaryStore.setFlushCompleteHook(null);
		sharedBackupStore.setFlushCompleteHook(null);
		if (primary != null) primary.close();
		if (backup != null) backup.close();
		if (primaryStore != null && primaryStore != sharedPrimaryStore) primaryStore.close();
		if (backupStore != null && backupStore != sharedBackupStore) backupStore.close();
		sharedPrimaryStore.close();
		sharedBackupStore.close();
	}

	/**
	 * Helper: write a test value at [:data hash] into a node server.
	 */
	@SuppressWarnings("unchecked")
	private void writeDataValue(NodeServer<?> server, long value) {
		Keyword dataKey = Keyword.intern("data");
		ACell testValue = CVMLong.create(value);
		Hash valueHash = Hash.get(testValue);

		Index<Hash, ACell> dataIndex = (Index<Hash, ACell>) server.getCursor().get(dataKey);
		if (dataIndex == null) {
			dataIndex = (Index<Hash, ACell>) Index.EMPTY;
		}
		dataIndex = dataIndex.assoc(valueHash, testValue);
		server.getCursor().assoc(dataKey, dataIndex);
	}

	/**
	 * Helper: read a test value from [:data hash] in a node server.
	 */
	private ACell readDataValue(NodeServer<?> server, long value) {
		Keyword dataKey = Keyword.intern("data");
		ACell testValue = CVMLong.create(value);
		Hash valueHash = Hash.get(testValue);
		return RT.getIn(server.getLocalValue(), dataKey, valueHash);
	}

	/**
	 * Helper: connect primary → backup (primary broadcasts to backup).
	 */
	private void connectPrimaryToBackup() throws Exception {
		InetSocketAddress backupAddr = backup.getHostAddress();
		AccountKey peerKey = AKeyPair.generate().getAccountKey();
		Convex conn = ConvexRemote.connect(backupAddr);
		propagationGroup(primary).addPeer(peerKey, conn);
	}

	/**
	 * Helper: sync primary's cursor to its propagator, then pull into backup.
	 *
	 * <p>The sync ensures primary's propagator has the latest cursor value
	 * (announced + persisted) so that LATTICE_QUERY responses are up to date.
	 * Without this, the propagator would return null for values written to
	 * the cursor but not yet announced.
	 */
	private void syncBackupFromPrimary() throws Exception {
		// Sync primary so propagator has the latest value for query responses.
		ACell expected=primary.getCursor().get();
		CompletableFuture<ACell> announced=nextAnnouncement(
			propagationGroup(primary),expected,16);
		primary.getCursor().sync();
		announced.get(5,TimeUnit.SECONDS);

		pullBackupFromPrimary();
	}

	/** Waits on propagation signals for one exact authoritative snapshot. */
	private static CompletableFuture<ACell> nextAnnouncement(
			LatticePropagator propagator,ACell expected,int remaining) {
		return propagator.nextAnnounce().thenCompose(value -> {
			if (expected.equals(value)) return CompletableFuture.completedFuture(value);
			if (remaining<=1) return CompletableFuture.failedFuture(
				new AssertionError("Expected propagation snapshot did not arrive"));
			return nextAnnouncement(propagator,expected,remaining-1);
		});
	}

	/** Pulls the currently announced primary snapshot without syncing it first. */
	private void pullBackupFromPrimary() throws Exception {
		InetSocketAddress primaryAddr = primary.getHostAddress();
		AccountKey peerKey = AKeyPair.generate().getAccountKey();
		Convex conn = ConvexRemote.connect(primaryAddr);
		LatticePropagator group=propagationGroup(backup);
		try {
			group.addPeer(peerKey, conn);
			assertTrue(backup.pull(group), "Pull should complete");
		} finally {
			group.removePeer(peerKey);
			conn.close();
		}
	}

	/**
	 * Helper: explicitly persist the current value.
	 */
	private void persistCurrent(NodeServer<?> server) throws IOException {
		server.persistSnapshot(server.getLocalValue());
	}

	/** Adds enough small values to ensure the {@code :data} child is non-embedded. */
	private void writeNonEmbeddedRoot(NodeServer<?> server) {
		for (int i = 0; i < 20; i++) {
			writeDataValue(server, i);
		}
	}

	@SuppressWarnings("unchecked")
	private void assertStoreBackedRoot(ACell value) {
		// The small top-level Index is embedded and therefore keeps a direct ref.
		// Its large :data value is the first non-embedded persistence boundary.
		ACell data = ((Index<Keyword, ACell>) value).get(Keyword.intern("data"));
		assertNotNull(data);
		assertTrue(data.getRef() instanceof RefSoft,
			"synced non-embedded :data child should have a soft store reference");
		assertSame(primaryStore, ((RefSoft<?>) data.getRef()).getStore(),
			"synced child reference should be bound to the primary store");
	}

	// ========== Tests ==========

	/**
	 * Core scenario: primary writes data, broadcasts to backup,
	 * both persist. Restart both, verify data survives.
	 */
	@Test
	public void testPrimaryBackupPersistAndRestore() throws Exception {
		// Launch primary and backup
		primary = new NodeServer<>(Lattice.ROOT, primaryStore);
		backup = new NodeServer<>(Lattice.ROOT, backupStore);
		serveInbound(primary);
		addPropagationGroup(backup);
		primary.launch();
		backup.launch();

		// Connect primary → backup
		connectPrimaryToBackup();

		// Write data on primary
		writeDataValue(primary, 42);
		writeDataValue(primary, 99);

		// Sync backup to ensure it received the data
		syncBackupFromPrimary();

		// Verify backup has the data
		assertEquals(CVMLong.create(42), readDataValue(backup, 42));
		assertEquals(CVMLong.create(99), readDataValue(backup, 99));

		// Close both (triggers final persist)
		primary.close();
		backup.close();
		primary = null;
		backup = null;

		// Restart primary from same store — should restore
		primary = new NodeServer<>(Lattice.ROOT, primaryStore);
		primary.launch();
		assertEquals(CVMLong.create(42), readDataValue(primary, 42),
			"Primary should restore value 42 from store");
		assertEquals(CVMLong.create(99), readDataValue(primary, 99),
			"Primary should restore value 99 from store");

		// Restart backup from same store — should restore
		backup = new NodeServer<>(Lattice.ROOT, backupStore);
		backup.launch();
		assertEquals(CVMLong.create(42), readDataValue(backup, 42),
			"Backup should restore value 42 from store");
		assertEquals(CVMLong.create(99), readDataValue(backup, 99),
			"Backup should restore value 99 from store");
	}

	/**
	 * Test that restore=false gives a clean start even on a persistent store.
	 */
	@Test
	public void testRestoreDisabled() throws Exception {
		// Write and persist some data
		primary = new NodeServer<>(Lattice.ROOT, primaryStore);
		primary.launch();
		writeDataValue(primary, 777);
		primary.close();
		primary = null;

		// Restart with restore=false
		NodeConfig noRestore = NodeConfig.create(Maps.of(NodeConfig.RESTORE, CVMBool.FALSE));
		primary = new NodeServer<>(Lattice.ROOT, primaryStore, noRestore);
		primary.launch();

		// Value should NOT be restored
		assertNull(readDataValue(primary, 777),
			"Value should not be restored when restore=false");
	}

	/**
	 * Test that persist=false prevents writing to store.
	 */
	@Test
	public void testPersistDisabled() throws Exception {
		// Launch with persist=false
		NodeConfig noPersist = NodeConfig.create(Maps.of(NodeConfig.PERSIST, CVMBool.FALSE));
		primary = new NodeServer<>(Lattice.ROOT, primaryStore, noPersist);
		primary.launch();

		// Write data
		writeDataValue(primary, 888);
		primary.close();
		primary = null;

		// Restart with defaults (restore=true)
		primary = new NodeServer<>(Lattice.ROOT, primaryStore);
		primary.launch();

		// Value should NOT be there (was never persisted)
		assertNull(readDataValue(primary, 888),
			"Value should not survive restart when persist=false");
	}

	/**
	 * Test with MemoryStore — persist/restore are no-ops, no errors.
	 */
	@Test
	public void testMemoryStoreNoPersistence() throws Exception {
		primaryStore = new MemoryStore();

		primary = new NodeServer<>(Lattice.ROOT, primaryStore);
		primary.launch();

		writeDataValue(primary, 555);
		assertEquals(CVMLong.create(555), readDataValue(primary, 555));

		// Close and restart — value should be gone
		primary.close();
		primary = null;

		primary = new NodeServer<>(Lattice.ROOT, primaryStore);
		primary.launch();
		assertNull(readDataValue(primary, 555),
			"MemoryStore should not persist data across restarts");
	}

	/**
	 * Test that data written on primary survives primary restart,
	 * and backup can still sync from restarted primary.
	 */
	@Test
	public void testPrimaryRestartThenSync() throws Exception {
		// Launch primary, write data, close
		primary = new NodeServer<>(Lattice.ROOT, primaryStore);
		primary.launch();
		writeDataValue(primary, 100);
		writeDataValue(primary, 200);
		primary.close();
		primary = null;

		// Restart primary
		primary = new NodeServer<>(Lattice.ROOT, primaryStore);
		serveInbound(primary);
		primary.launch();

		// Verify restored
		assertEquals(CVMLong.create(100), readDataValue(primary, 100));
		assertEquals(CVMLong.create(200), readDataValue(primary, 200));

		// Launch backup and sync from restarted primary
		backup = new NodeServer<>(Lattice.ROOT, backupStore);
		addPropagationGroup(backup);
		backup.launch();
		pullBackupFromPrimary();

		// Backup should now have the data
		assertEquals(CVMLong.create(100), readDataValue(backup, 100),
			"Backup should receive value 100 from restarted primary");
		assertEquals(CVMLong.create(200), readDataValue(backup, 200),
			"Backup should receive value 200 from restarted primary");
	}

	/**
	 * Test that backup can restore independently after receiving broadcast.
	 */
	@Test
	public void testBackupRestoreAfterBroadcast() throws Exception {
		// Launch both
		primary = new NodeServer<>(Lattice.ROOT, primaryStore);
		backup = new NodeServer<>(Lattice.ROOT, backupStore);
		serveInbound(primary);
		addPropagationGroup(backup);
		primary.launch();
		backup.launch();

		// Primary writes and broadcasts to backup
		connectPrimaryToBackup();
		writeDataValue(primary, 300);
		syncBackupFromPrimary();

		assertEquals(CVMLong.create(300), readDataValue(backup, 300));

		// Close only backup (triggers persist)
		backup.close();
		backup = null;

		// Primary writes more data while backup is down
		writeDataValue(primary, 400);

		// Restart backup — should restore value 300 from its own store
		backup = new NodeServer<>(Lattice.ROOT, backupStore);
		addPropagationGroup(backup);
		backup.launch();
		assertEquals(CVMLong.create(300), readDataValue(backup, 300),
			"Backup should restore value 300 from its own store");

		// Value 400 was written while backup was down — not yet synced
		assertNull(readDataValue(backup, 400),
			"Backup should not have value 400 yet (written while down)");

		// Sync backup from primary to get the new data
		syncBackupFromPrimary();
		assertEquals(CVMLong.create(400), readDataValue(backup, 400),
			"Backup should receive value 400 after sync");
	}

	/**
	 * Test that the root data in the store is actually the full lattice value.
	 */
	@Test
	public void testStoreRootDataIsLatticeValue() throws Exception {
		primary = new NodeServer<>(Lattice.ROOT, primaryStore);
		primary.launch();

		writeDataValue(primary, 12345);

		// Persist explicitly
		persistCurrent(primary);

		// Read root data directly from store
		ACell rootData = primaryStore.getRootData();
		assertNotNull(rootData, "Store should have root data after persist");

		// Verify the root data contains our value
		ACell readBack = RT.getIn(rootData,
			Keyword.intern("data"), Hash.get(CVMLong.create(12345)));
		assertEquals(CVMLong.create(12345), readBack,
			"Store root data should contain the persisted value");
	}

	/**
	 * Test that cursor.sync() triggers propagator persist (without relying on close).
	 * This is the pattern the venue uses: write → sync → propagator persists.
	 */
	@Test
	public void testSyncTriggersPersist() throws Exception {
		primary = new NodeServer<>(Lattice.ROOT, primaryStore);
		primary.launch();

		writeDataValue(primary, 42);

		// Sync cursor — synchronous publication on the primary completes announce
		// + setRootData on this thread before returning.
		primary.getCursor().sync();

		// Store should have the data without needing close()
		ACell rootData = primaryStore.getRootData();
		assertNotNull(rootData, "Store should have root data after sync");

		ACell readBack = RT.getIn(rootData,
			Keyword.intern("data"), Hash.get(CVMLong.create(42)));
		assertEquals(CVMLong.create(42), readBack,
			"Store root data should contain the value after sync (no close needed)");
	}

	/**
	 * Test sync+persist with local-only config (port=-1), matching venue setup.
	 */
	@Test
	public void testSyncPersistLocalOnly() throws Exception {
		// Local-only mode, same as venue: NodeConfig.port(-1)
		primary = new NodeServer<>(Lattice.ROOT, primaryStore, NodeConfig.port(-1));
		primary.launch();

		writeDataValue(primary, 77);
		primary.getCursor().sync();

		// Verify persisted
		ACell rootData = primaryStore.getRootData();
		assertNotNull(rootData, "Local-only node should persist after sync");

		ACell readBack = RT.getIn(rootData,
			Keyword.intern("data"), Hash.get(CVMLong.create(77)));
		assertEquals(CVMLong.create(77), readBack,
			"Local-only node should persist data after sync");

		// Close and restore
		primary.close();
		primary = null;

		primary = new NodeServer<>(Lattice.ROOT, primaryStore, NodeConfig.port(-1));
		primary.launch();
		assertEquals(CVMLong.create(77), readDataValue(primary, 77),
			"Local-only node should restore data from store");
	}

	/**
	 * A quiescent sync installs the exact store-backed root returned by the
	 * authoritative node store. Repeating the sync must reuse that same identity.
	 */
	@Test
	public void testSyncConvergesToStableStoreBackedIdentity() throws Exception {
		primary = new NodeServer<>(Lattice.ROOT, primaryStore, NodeConfig.port(-1));
		primary.launch();
		writeNonEmbeddedRoot(primary);

		ACell first = primary.getCursor().sync();
		assertSame(first, primary.getCursor().get(),
			"root cursor should install the value returned by synchronous persistence");
		assertSame(first, primaryStore.getRootData(),
			"cursor and store should expose the same canonical root object");
		assertStoreBackedRoot(first);

		ACell second = primary.getCursor().sync();
		assertSame(first, second, "an unchanged sync should reuse the canonical root identity");
		assertSame(first, primary.getCursor().get());
		assertSame(first, primaryStore.getRootData());
	}

	/**
	 * A concurrent write may make the first sync miss its installation CAS. The
	 * write must survive, and the next quiescent sync must converge the cursor and
	 * store to one exact store-backed root identity.
	 */
	@Test
	public void testQuiescentSyncConvergesIdentityAfterRacedSync() throws Exception {
		CountDownLatch persistenceEntered = new CountDownLatch(1);
		CountDownLatch allowPersistence = new CountDownLatch(1);
		AtomicInteger rootWrites = new AtomicInteger();

		primary = new NodeServer<>(Lattice.ROOT, primaryStore, NodeConfig.port(-1));
		primary.launch();

		sharedPrimaryStore.setRootWriteHook(() -> {
			if (rootWrites.getAndIncrement() == 0) {
				persistenceEntered.countDown();
				try {
					if (!allowPersistence.await(5, TimeUnit.SECONDS)) {
						throw new IOException("Timed out waiting to release test persistence");
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while testing raced sync", e);
				}
			}
		});
		writeNonEmbeddedRoot(primary);

		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread syncThread = new Thread(() -> {
			try {
				primary.getCursor().sync();
			} catch (Throwable e) {
				failure.set(e);
			}
		}, "raced-identity-sync");
		syncThread.start();

		assertTrue(persistenceEntered.await(5, TimeUnit.SECONDS),
			"sync should reach persistence before the concurrent write");
		writeDataValue(primary, 999);
		allowPersistence.countDown();
		syncThread.join(5_000);

		assertFalse(syncThread.isAlive(), "raced sync should complete promptly");
		assertNull(failure.get(), "raced sync should not fail");
		assertEquals(CVMLong.create(999), readDataValue(primary, 999),
			"concurrent write must survive the raced sync");

		ACell converged = primary.getCursor().sync();
		assertSame(converged, primary.getCursor().get());
		assertSame(converged, primaryStore.getRootData(),
			"quiescent sync should converge cursor and store root identities");
		assertStoreBackedRoot(converged);
	}

	/**
	 * Synchronous publication must surface store errors to the caller. If
	 * setRootData throws, cursor.sync() must throw rather than falsely confirming
	 * root publication.
	 */
	@Test
	public void testSyncSurfacesPersistenceFailure() throws Exception {
		primary = new NodeServer<>(Lattice.ROOT, primaryStore, NodeConfig.port(-1));
		primary.launch();
		writeDataValue(primary, 99);
		sharedPrimaryStore.setRootWriteHook(() -> {
			throw new IOException("simulated disk failure");
		});

		// sync() must propagate, not swallow
		StoreException ex = assertThrows(StoreException.class,
			() -> primary.getCursor().sync(),
			"sync() must throw when setRootData fails");
		assertTrue(ex.getCause() instanceof IOException,
			"Cause must be the original IOException, was: " + ex.getCause());
		assertEquals("simulated disk failure", ex.getCause().getMessage());
		assertTrue(primary.isRunning(), "sync failure must not impose an operator recovery policy");
	}

	@Test
	public void testLaunchCompletesInitialCheckpoint() throws Exception {
		AtomicInteger flushes=new AtomicInteger();
		sharedPrimaryStore.setFlushHook(flushes::incrementAndGet);
		primary=new NodeServer<>(Lattice.ROOT,primaryStore,NodeConfig.port(-1));

		primary.launch();

		assertEquals(1,flushes.get(),"launch must checkpoint the initial published root");
	}

	@Test
	public void testExplicitCheckpointCoalescesCleanStore() throws Exception {
		primary=new NodeServer<>(Lattice.ROOT,primaryStore,NodeConfig.port(-1));
		primary.launch();
		AtomicInteger flushes=new AtomicInteger();
		sharedPrimaryStore.setFlushHook(flushes::incrementAndGet);
		writeDataValue(primary,102);
		primary.getCursor().sync();

		assertTrue(primary.checkpoint());
		assertFalse(primary.checkpoint(),"a clean store must not be flushed again");
		assertEquals(1,flushes.get());
	}

	@Test
	public void testFailedCheckpointRemainsDirtyForRetry() throws Exception {
		primary=new NodeServer<>(Lattice.ROOT,primaryStore,NodeConfig.port(-1));
		primary.launch();
		writeDataValue(primary,103);
		primary.getCursor().sync();
		AtomicInteger attempts=new AtomicInteger();
		sharedPrimaryStore.setFlushHook(()->{
			if (attempts.incrementAndGet()==1) throw new IOException("simulated checkpoint failure");
		});

		IOException failure=assertThrows(IOException.class,primary::checkpoint);
		assertEquals("simulated checkpoint failure",failure.getMessage());
		assertTrue(primary.checkpoint(),"a failed barrier must leave the store dirty");
		assertFalse(primary.checkpoint());
		assertEquals(2,attempts.get());
	}

	@Test
	public void testPeriodicCheckpointUsesMaintenanceLoop() throws Exception {
		NodeConfig config=NodeConfig.create(Maps.of(
				NodeConfig.PORT,CVMLong.create(-1),
				NodeConfig.PERSIST_INTERVAL,CVMLong.create(20)));
		primary=new NodeServer<>(Lattice.ROOT,primaryStore,config);
		primary.launch();
		CountDownLatch checkpointed=new CountDownLatch(1);
		AtomicInteger flushes=new AtomicInteger();
		sharedPrimaryStore.setFlushCompleteHook(()->{
			flushes.incrementAndGet();
			checkpointed.countDown();
		});
		writeDataValue(primary,104);
		primary.getCursor().sync();

		assertTrue(checkpointed.await(2,TimeUnit.SECONDS),"periodic checkpoint signal not received");
		assertEquals(1,flushes.get());
	}

	@Test
	public void testPeriodicCheckpointRetriesFailure() throws Exception {
		NodeConfig config=NodeConfig.create(Maps.of(
				NodeConfig.PORT,CVMLong.create(-1),
				NodeConfig.PERSIST_INTERVAL,CVMLong.create(20)));
		primary=new NodeServer<>(Lattice.ROOT,primaryStore,config);
		primary.launch();
		CountDownLatch checkpointed=new CountDownLatch(1);
		AtomicInteger attempts=new AtomicInteger();
		sharedPrimaryStore.setFlushHook(()->{
			if (attempts.incrementAndGet()==1) throw new IOException("simulated periodic failure");
		});
		sharedPrimaryStore.setFlushCompleteHook(checkpointed::countDown);
		writeDataValue(primary,107);
		primary.getCursor().sync();

		assertTrue(checkpointed.await(2,TimeUnit.SECONDS),"periodic checkpoint retry not received");
		assertEquals(2,attempts.get());
	}

	@Test
	public void testPeriodicCheckpointAppliesToCustomPrimary() throws Exception {
		NodeConfig config=NodeConfig.create(Maps.of(
				NodeConfig.PORT,CVMLong.create(-1),
				NodeConfig.PERSIST_INTERVAL,CVMLong.create(20)));
		primary=new NodeServer<>(Lattice.ROOT,primaryStore,config);
		primary.addPropagator(new LatticePropagator(
			primaryStore,Lattice.ROOT,value -> value,config));
		primary.launch();
		CountDownLatch checkpointed=new CountDownLatch(1);
		sharedPrimaryStore.setFlushCompleteHook(checkpointed::countDown);
		writeDataValue(primary,108);
		primary.getCursor().sync();

		assertTrue(checkpointed.await(2,TimeUnit.SECONDS),"custom primary was not checkpointed");
	}

	@Test
	public void testCloseCompletesFinalCheckpoint() throws Exception {
		primary=new NodeServer<>(Lattice.ROOT,primaryStore,NodeConfig.port(-1));
		primary.launch();
		AtomicInteger flushes=new AtomicInteger();
		sharedPrimaryStore.setFlushHook(flushes::incrementAndGet);
		writeDataValue(primary,105);

		primary.close();

		assertEquals(1,flushes.get());
		assertNotNull(readDataValue(primary,105));
		assertNotNull(RT.getIn(primaryStore.getRootData(),Keyword.intern("data"),Hash.get(CVMLong.create(105))));
	}

	@Test
	public void testCloseSurfacesFinalCheckpointFailureAfterStopping() throws Exception {
		primary=new NodeServer<>(Lattice.ROOT,primaryStore,NodeConfig.port(-1));
		primary.launch();
		writeDataValue(primary,106);
		sharedPrimaryStore.setFlushHook(()->{
			throw new IOException("simulated final checkpoint failure");
		});

		IOException failure=assertThrows(IOException.class,primary::close);
		sharedPrimaryStore.setFlushHook(null);
		assertEquals("simulated final checkpoint failure",failure.getMessage());
		assertFalse(primary.isRunning(),"resources must be stopped even when the final barrier fails");
		assertEquals(NodeServer.LifecycleState.STOPPED,primary.getLifecycleState());
	}

	@Test
	public void testSyncPublishesWithoutDurabilityBarrier() throws Exception {
		primary = new NodeServer<>(Lattice.ROOT, primaryStore, NodeConfig.port(-1));
		primary.launch();
		AtomicInteger flushes=new AtomicInteger();
		sharedPrimaryStore.setFlushHook(flushes::incrementAndGet);
		writeDataValue(primary,100);

		ACell synced=primary.getCursor().sync();

		assertEquals(0,flushes.get(),"sync must not force a physical durability barrier");
		assertSame(synced,primaryStore.getRootData(),"sync must publish the root to the primary store");
	}

	@Test
	public void testSyncDoesNotInvokeDurabilityBarrier() throws Exception {
		primary = new NodeServer<>(Lattice.ROOT, primaryStore, NodeConfig.port(-1));
		primary.launch();
		writeDataValue(primary,101);
		sharedPrimaryStore.setFlushHook(()->{
			throw new IOException("simulated flush failure");
		});

		ACell synced=primary.getCursor().sync();

		assertSame(synced,primaryStore.getRootData(),"sync must still publish with a failing flush hook");
		assertTrue(primary.isRunning());
	}

	@Test
	public void testExplicitPersistSurfacesDurabilityBarrierFailure() throws Exception {
		primary = new NodeServer<>(Lattice.ROOT, primaryStore, NodeConfig.port(-1));
		primary.launch();
		sharedPrimaryStore.setFlushHook(()->{
			throw new IOException("simulated explicit flush failure");
		});

		IOException ex=assertThrows(IOException.class,
				()->primary.persistSnapshot(primary.getLocalValue()));
		assertEquals("simulated explicit flush failure",ex.getMessage());
	}

	/**
	 * Concurrent app writes during sync must not be lost. Thread A calls
	 * {@code sync()} (announce + setRootData on caller's thread); thread B
	 * writes a new key to the cursor mid-sync. After both, both writes must
	 * be visible — the {@code RootLatticeCursor.sync()} CAS-or-merge fallback
	 * is what guarantees this.
	 *
	 * <p>Thread B writes at a fresh top-level key per iteration via the
	 * cursor's atomic {@code assoc}, so the only relevant race is the one
	 * inside sync (B's write landing between A's snapshot capture and A's CAS).
	 * The iteration count is high enough to make the race likely to be hit.
	 */
	@Test
	public void testConcurrentWriteDuringSync() throws Exception {
		primary = new NodeServer<>(Lattice.ROOT, primaryStore, NodeConfig.port(-1));
		primary.launch();

		Keyword stable = Keyword.intern("stable");
		primary.getCursor().assoc(stable, CVMLong.create(1));

		for (int i = 0; i < 50; i++) {
			final Keyword bKey = Keyword.intern("b-" + i);
			final ACell bValue = CVMLong.create(i);

			CountDownLatch ready = new CountDownLatch(1);
			Thread b = new Thread(() -> {
				try {
					ready.await();
					primary.getCursor().assoc(bKey, bValue);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
			b.start();
			ready.countDown();
			primary.getCursor().sync();
			b.join();

			// B's write must survive the CAS-or-merge fallback inside sync()
			assertEquals(bValue, primary.getCursor().get(bKey),
				"Concurrent write at " + bKey + " must not be lost by sync at iteration " + i);
			assertEquals(CVMLong.create(1), primary.getCursor().get(stable),
				"Pre-existing :stable must survive concurrent sync at iteration " + i);
		}
	}

	/**
	 * Sole-writer invariant: authoritative persistence pipelines through
	 * NodeServer must not interleave. Propagators no longer write the node root.
	 *
	 * <p>The test instruments {@code setRootData} to dwell inside the pipeline
	 * and count concurrent pipeline activity. With the propagator's writeLock,
	 * max-in-flight is exactly 1; without it, the dwell would let a second
	 * persistence call enter while the first is still executing.
	 */
	@Test
	public void testAuthoritativePersistencePipelinesAreSerialised() throws Exception {
		AtomicInteger inFlight = new AtomicInteger();
		AtomicInteger maxInFlight = new AtomicInteger();
		AtomicInteger rootWrites = new AtomicInteger();
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch secondStarted = new CountDownLatch(1);

		primary = new NodeServer<>(Lattice.ROOT, primaryStore, NodeConfig.port(-1));
		primary.launch();

		sharedPrimaryStore.setRootWriteHook(() -> {
			int n = inFlight.incrementAndGet();
			maxInFlight.updateAndGet(m -> Math.max(m, n));
			try {
				if (rootWrites.getAndIncrement() == 0) {
					firstEntered.countDown();
					if (!releaseFirst.await(5,TimeUnit.SECONDS)) {
						throw new IOException("Timed out waiting to release first snapshot");
					}
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while testing snapshot serialisation", e);
			} finally {
				inFlight.decrementAndGet();
			}
		});
		AtomicReference<Throwable> failure = new AtomicReference<>();

		// Two distinct snapshots (lattice-ordered) for the two threads.
		primary.getCursor().assoc(Keyword.intern("a"), CVMLong.create(1));
		final ACell v1 = primary.getCursor().get();
		primary.getCursor().assoc(Keyword.intern("b"), CVMLong.create(2));
		final ACell v2 = primary.getCursor().get();

		Thread t1 = new Thread(() -> {
			try { primary.persistSnapshot(v1); }
			catch (Throwable e) { failure.compareAndSet(null, e); }
		}, "snapshot-A");
		Thread t2 = new Thread(() -> {
			secondStarted.countDown();
			try { primary.persistSnapshot(v2); }
			catch (Throwable e) { failure.compareAndSet(null, e); }
		}, "snapshot-B");

		t1.start();
		try {
			assertTrue(firstEntered.await(5,TimeUnit.SECONDS));
			t2.start();
			assertTrue(secondStarted.await(5,TimeUnit.SECONDS));
			assertEquals(1,maxInFlight.get(),"second pipeline must wait outside setRootData");
		} finally {
			releaseFirst.countDown();
		}
		t1.join();
		t2.join();

		assertNull(failure.get(),"authoritative persistence must not throw");
		assertEquals(1, maxInFlight.get(),
			"authoritative persistence pipelines must not overlap");
	}
}
