package convex.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.lattice.ALattice;
import convex.lattice.Lattice;

/**
 * Tests for automatic lattice propagation using LatticePropagator.
 */
public class LatticePropagatorTest {

	private ALattice<?> lattice;
	private NodeServer<?> server1;
	private NodeServer<?> server2;
	private AStore store1;
	private AStore store2;

	@BeforeEach
	public void setUp() throws IOException, InterruptedException {
		// Use the base lattice
		lattice = Lattice.ROOT;

		// Create two NodeServers
		store1 = new MemoryStore();
		store2 = new MemoryStore();

		server1 = new NodeServer<>(lattice, store1, 19600);
		server2 = new NodeServer<>(lattice, store2, 19601);

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
			server1.addPeer(peer1to2);

			Convex peer2to1 = ConvexRemote.connect(server1Address);
			server2.addPeer(peer2to1);
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
		assertNotNull(server1.getPropagator(), "Propagator should be created on launch");
		assertTrue(server1.getPropagator().isRunning(), "Propagator should be running");

		assertNotNull(server2.getPropagator(), "Propagator should be created on launch");
		assertTrue(server2.getPropagator().isRunning(), "Propagator should be running");
	}

	/**
	 * Tests that automatic propagation broadcasts updates to connected peers.
	 *
	 * This test verifies that:
	 * 1. The propagator detects value changes
	 * 2. The propagator broadcasts to connected peers
	 * 3. Broadcasts are sent automatically without manual intervention
	 * 4. broadcast value is successfully obtained
	 */
	@Test
	public void testAutomaticPropagation() throws Exception {
		// Get initial broadcast count
		long initialBroadcastCount = server2.getPropagator().getBroadcastCount();

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
		server2.updateLocalPath(updatedDataIndex, dataKeyword);

		// Wait for propagator to detect change and broadcast (up to 1 second)
		server1.sync();

		// Verify propagator detected the change and broadcasted
		long finalBroadcastCount = server2.getPropagator().getBroadcastCount();
		assertTrue(finalBroadcastCount > initialBroadcastCount,
			"Propagator should have detected value change and broadcasted to peers");

		// Verify the broadcast count increased by at least 1
		assertTrue((finalBroadcastCount - initialBroadcastCount) >= 1,
			"At least one broadcast should have been sent after value change");
		
		assertEquals(testValue,RT.getIn(server1.getLocalValue(),dataKeyword,valueHash));
	}

	/**
	 * Tests that propagator broadcast count increases with local updates.
	 */
	@Test
	public void testPropagatorBroadcastCount() throws Exception {
		// Get initial broadcast count
		long initialCount = server1.getPropagator().getBroadcastCount();

		// Perform multiple local updates to trigger broadcasts
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
			server1.updateLocalPath(updatedDataIndex, dataKeyword);

			// Small delay between updates to allow broadcasts to complete
			TimeUnit.MILLISECONDS.sleep(100);
		}

		// Wait a bit for final broadcast to complete
		TimeUnit.MILLISECONDS.sleep(200);

		// Verify count increased
		long finalCount = server1.getPropagator().getBroadcastCount();
		assertTrue(finalCount > initialCount,
			"Propagator broadcast count should increase with local updates");
		assertTrue((finalCount - initialCount) >= 3,
			"Should have at least 3 broadcasts from 3 local updates");
	}
}
