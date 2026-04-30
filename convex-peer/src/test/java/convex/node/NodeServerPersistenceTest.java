package convex.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.etch.EtchStore;
import convex.lattice.Lattice;

/**
 * Integration tests for NodeServer persistence and replication.
 *
 * Tests a primary + backup scenario with EtchStore persistence,
 * verifying broadcast, stop/start, and restore at various points.
 */
public class NodeServerPersistenceTest {

	private NodeServer<?> primary;
	private NodeServer<?> backup;
	private AStore primaryStore;
	private AStore backupStore;

	@AfterEach
	public void tearDown() throws IOException {
		if (primary != null) primary.close();
		if (backup != null) backup.close();
		if (primaryStore != null) primaryStore.close();
		if (backupStore != null) backupStore.close();
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
		primary.getPropagator().addPeer(peerKey, conn);
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
		// Synchronous commit guarantees announce + setRootData complete before
		// sync() returns — the announced cursor is up to date.
		primary.getCursor().sync();

		InetSocketAddress primaryAddr = primary.getHostAddress();
		AccountKey peerKey = AKeyPair.generate().getAccountKey();
		Convex conn = ConvexRemote.connect(primaryAddr);
		try {
			backup.getPropagator().addPeer(peerKey, conn);
			assertTrue(backup.pull(), "Pull should complete");
		} finally {
			backup.getPropagator().removePeer(peerKey);
			conn.close();
		}
	}

	/**
	 * Helper: explicitly persist the current value.
	 */
	private void persistCurrent(NodeServer<?> server) throws IOException {
		server.persistSnapshot(server.getLocalValue());
	}

	// ========== Tests ==========

	/**
	 * Core scenario: primary writes data, broadcasts to backup,
	 * both persist. Restart both, verify data survives.
	 */
	@Test
	public void testPrimaryBackupPersistAndRestore() throws Exception {
		primaryStore = EtchStore.createTemp("primary");
		backupStore = EtchStore.createTemp("backup");

		// Launch primary and backup
		primary = new NodeServer<>(Lattice.ROOT, primaryStore);
		backup = new NodeServer<>(Lattice.ROOT, backupStore);
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
		primaryStore = EtchStore.createTemp("primary");

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
		primaryStore = EtchStore.createTemp("primary");

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
		primaryStore = EtchStore.createTemp("primary");
		backupStore = EtchStore.createTemp("backup");

		// Launch primary, write data, close
		primary = new NodeServer<>(Lattice.ROOT, primaryStore);
		primary.launch();
		writeDataValue(primary, 100);
		writeDataValue(primary, 200);
		primary.close();
		primary = null;

		// Restart primary
		primary = new NodeServer<>(Lattice.ROOT, primaryStore);
		primary.launch();

		// Verify restored
		assertEquals(CVMLong.create(100), readDataValue(primary, 100));
		assertEquals(CVMLong.create(200), readDataValue(primary, 200));

		// Launch backup and sync from restarted primary
		backup = new NodeServer<>(Lattice.ROOT, backupStore);
		backup.launch();
		syncBackupFromPrimary();

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
		primaryStore = EtchStore.createTemp("primary");
		backupStore = EtchStore.createTemp("backup");

		// Launch both
		primary = new NodeServer<>(Lattice.ROOT, primaryStore);
		backup = new NodeServer<>(Lattice.ROOT, backupStore);
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
		primaryStore = EtchStore.createTemp("primary");

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
		primaryStore = EtchStore.createTemp("primary");

		primary = new NodeServer<>(Lattice.ROOT, primaryStore);
		primary.launch();

		writeDataValue(primary, 42);

		// Sync cursor — synchronous commit on the primary completes announce
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
		primaryStore = EtchStore.createTemp("primary");

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
	 * Synchronous commit must surface persistence errors to the caller. If
	 * setRootData throws, cursor.sync() must throw — silent loss of durability
	 * is the failure mode this design rules out.
	 */
	@Test
	public void testSyncSurfacesPersistenceFailure() throws Exception {
		// EtchStore subclass that throws on setRootData to simulate a disk error
		EtchStore failingStore = new EtchStore(EtchStore.createTemp("failing").getEtch()) {
			@Override
			public <T extends ACell> convex.core.data.Ref<T> setRootData(T data) throws IOException {
				throw new IOException("simulated disk failure");
			}
		};
		primaryStore = failingStore;

		primary = new NodeServer<>(Lattice.ROOT, primaryStore, NodeConfig.port(-1));
		primary.launch();
		writeDataValue(primary, 99);

		// sync() must propagate, not swallow
		RuntimeException ex = assertThrows(RuntimeException.class,
			() -> primary.getCursor().sync(),
			"sync() must throw when setRootData fails");
		assertTrue(ex.getCause() instanceof IOException,
			"Cause must be the original IOException, was: " + ex.getCause());
		assertEquals("simulated disk failure", ex.getCause().getMessage());
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
		primaryStore = EtchStore.createTemp("primary-concurrent");
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
}
