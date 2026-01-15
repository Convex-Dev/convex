package convex.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.data.ASet;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Sets;
import convex.core.data.Vectors;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.message.MessageType;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.lattice.ALattice;
import convex.lattice.cursor.ACursor;
import convex.lattice.generic.MaxLattice;
import convex.lattice.generic.SetLattice;

/**
 * Tests for NodeServer class.
 * 
 * Basic smoke tests for creating and operating a local NodeServer instance.
 */
public class NodeServerTest {

	private NodeServer<AInteger> maxNodeServer;
	private NodeServer<ASet<ACell>> setNodeServer;
	private AStore store;

	@BeforeEach
	public void setUp() {
		store = new MemoryStore();
	}

	@AfterEach
	public void tearDown() throws IOException {
		if (maxNodeServer != null) {
			maxNodeServer.close();
		}
		if (setNodeServer != null) {
			setNodeServer.close();
		}
		if (store != null) {
			store.close();
		}
	}

	/**
	 * Test creating a NodeServer with MaxLattice
	 */
	@Test
	public void testCreateMaxLatticeServer() {
		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store, null);

		assertNotNull(maxNodeServer);
		assertNotNull(maxNodeServer.getLattice());
		assertNotNull(maxNodeServer.getStore());
		assertNotNull(maxNodeServer.getCursor());
		assertFalse(maxNodeServer.isRunning());
	}

	/**
	 * Test creating a NodeServer with SetLattice
	 */
	@Test
	public void testCreateSetLatticeServer() {
		ALattice<ASet<ACell>> lattice = SetLattice.create();
		setNodeServer = new NodeServer<>(lattice, store, null);

		assertNotNull(setNodeServer);
		assertNotNull(setNodeServer.getLattice());
		assertNotNull(setNodeServer.getStore());
		assertNotNull(setNodeServer.getCursor());
		assertFalse(setNodeServer.isRunning());
	}

	/**
	 * Test initial value is lattice zero
	 */
	@Test
	public void testInitialValue() {
		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store, null);

		AInteger initialValue = maxNodeServer.getLocalValue();
		assertNotNull(initialValue);
		assertEquals(CVMLong.ZERO, initialValue);
		assertEquals(lattice.zero(), initialValue);
	}

	/**
	 * Test value cursor operations
	 */
	@Test
	public void testValueCursor() {
		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store, null);

		ACursor<AInteger> cursor = maxNodeServer.getCursor();
		assertNotNull(cursor);

		// Initial value should be zero
		assertEquals(CVMLong.ZERO, cursor.get());

		// Set a new value
		cursor.set(CVMLong.ONE);
		assertEquals(CVMLong.ONE, cursor.get());
		assertEquals(CVMLong.ONE, maxNodeServer.getLocalValue());

		// Update using getAndSet
		AInteger oldValue = cursor.getAndSet(CVMLong.TWO);
		assertEquals(CVMLong.ONE, oldValue);
		assertEquals(CVMLong.TWO, cursor.get());
	}

	/**
	 * Test mergeValue method with MaxLattice
	 */
	@Test
	public void testMergeValue() {
		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store, null);

		// Start with zero
		assertEquals(CVMLong.ZERO, maxNodeServer.getLocalValue());

		// Merge with a value using the public mergeValue method
		AInteger merged = maxNodeServer.mergeValue(CVMLong.ONE);
		assertNotNull(merged);
		assertEquals(CVMLong.ONE, merged);
		assertEquals(CVMLong.ONE, maxNodeServer.getLocalValue());

		// Merge with larger value
		merged = maxNodeServer.mergeValue(CVMLong.create(5));
		assertNotNull(merged);
		assertEquals(CVMLong.create(5), merged);
		assertEquals(CVMLong.create(5), maxNodeServer.getLocalValue());

		// Merge with smaller value (should keep larger)
		merged = maxNodeServer.mergeValue(CVMLong.create(3));
		assertNotNull(merged);
		// Max lattice should keep the maximum value
		assertEquals(CVMLong.create(5), merged);
		assertEquals(CVMLong.create(5), maxNodeServer.getLocalValue());
		
		// Test merging null value (should return null)
		AInteger nullResult = maxNodeServer.mergeValue(null);
		assertEquals(null, nullResult);
	}

	/**
	 * Test mergeValue with SetLattice
	 */
	@Test
	public void testMergeSetValue() {
		ALattice<ASet<ACell>> lattice = SetLattice.create();
		setNodeServer = new NodeServer<>(lattice, store, null);

		// Start with empty set
		assertTrue(setNodeServer.getLocalValue().isEmpty());

		// Merge with a set containing values using the public mergeValue method
		ASet<ACell> merged = setNodeServer.mergeValue(Sets.of(CVMLong.ONE, CVMLong.TWO));
		assertNotNull(merged);
		assertTrue(merged.contains(CVMLong.ONE));
		assertTrue(merged.contains(CVMLong.TWO));
		assertEquals(2, merged.count());

		ASet<ACell> result = setNodeServer.getLocalValue();
		assertTrue(result.contains(CVMLong.ONE));
		assertTrue(result.contains(CVMLong.TWO));
		assertEquals(2, result.count());

		// Merge with overlapping set
		merged = setNodeServer.mergeValue(Sets.of(CVMLong.TWO, CVMLong.create(3)));
		assertNotNull(merged);
		assertTrue(merged.contains(CVMLong.ONE));
		assertTrue(merged.contains(CVMLong.TWO));
		assertTrue(merged.contains(CVMLong.create(3)));
		assertEquals(3, merged.count());

		result = setNodeServer.getLocalValue();
		assertTrue(result.contains(CVMLong.ONE));
		assertTrue(result.contains(CVMLong.TWO));
		assertTrue(result.contains(CVMLong.create(3)));
		assertEquals(3, result.count());
	}

	/**
	 * Test peer management with Convex connections
	 * TODO: should be using full connection manager
	 */
	@Test
	public void testPeerManagement() throws IOException, InterruptedException, java.util.concurrent.TimeoutException {
		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store, null);
		maxNodeServer.launch();

		// Create Convex connections to the server (using loopback addresses for testing)
		InetSocketAddress serverAddress = maxNodeServer.getHostAddress();
		ConvexRemote peer1 = ConvexRemote.connect(serverAddress);
		ConvexRemote peer2 = ConvexRemote.connect(serverAddress);

		// Initially no peers
		Set<Convex> peers = maxNodeServer.getPeerNodes();
		assertTrue(peers.isEmpty());

		// Add peers
		maxNodeServer.addPeer(peer1);
		maxNodeServer.addPeer(peer2);

		peers = maxNodeServer.getPeerNodes();
		assertEquals(2, peers.size());
		assertTrue(peers.contains(peer1));
		assertTrue(peers.contains(peer2));

		// Remove a peer
		maxNodeServer.removePeer(peer1);
		peers = maxNodeServer.getPeerNodes();
		assertEquals(1, peers.size());
		assertTrue(peers.contains(peer2));
		assertFalse(peers.contains(peer1));

		// Clean up
		peer1.close();
		peer2.close();
	}

	/**
	 * Test syncWithPeer using Convex connection
	 */
	@Test
	public void testSyncWithPeer() throws Exception {
		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store, null);
		maxNodeServer.launch();

		// Create a Convex connection to the server
		InetSocketAddress serverAddress = maxNodeServer.getHostAddress();
		ConvexRemote peer = ConvexRemote.connect(serverAddress);
		
		try {
			// Sync with peer - should get the current value (zero initially)
			CompletableFuture<AInteger> future = maxNodeServer.syncWithPeer(peer);
			AInteger result = future.get(5, TimeUnit.SECONDS);
			
			assertNotNull(result);
			assertEquals(CVMLong.ZERO, result); // Should return initial zero value
		} finally {
			peer.close();
		}
	}

	/**
	 * Test that server cannot be launched twice
	 */
	@Test
	public void testLaunchTwice() throws IOException, InterruptedException {
		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store, null);

		// First launch should work (even though network server is stubbed)
		maxNodeServer.launch();
		assertTrue(maxNodeServer.isRunning());

		// Second launch should throw exception
		assertThrows(IllegalStateException.class, () -> {
			maxNodeServer.launch();
		});
	}

	/**
	 * Test close operation
	 */
	@Test
	public void testClose() throws IOException, InterruptedException {
		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store, null);

		maxNodeServer.launch();
		assertTrue(maxNodeServer.isRunning());

		maxNodeServer.close();
		assertFalse(maxNodeServer.isRunning());

		// Closing again should be safe
		maxNodeServer.close();
		assertFalse(maxNodeServer.isRunning());
	}

	/**
	 * Test port configuration
	 */
	@Test
	public void testPortConfiguration() {
		ALattice<AInteger> lattice = MaxLattice.create();
		
		// Test with null port
		maxNodeServer = new NodeServer<>(lattice, store, null);
		assertEquals(null, maxNodeServer.getPort());

		// Test with specific port
		NodeServer<AInteger> server2 = new NodeServer<>(lattice, store, 19999);
		assertEquals(Integer.valueOf(19999), server2.getPort());
		
		try {
			server2.close();
		} catch (IOException e) {
			// Ignore
		}
	}

	/**
	 * Test that getLocalValue returns current cursor value
	 */
	@Test
	public void testGetLocalValue() {
		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store, null);

		// Initial value
		AInteger value1 = maxNodeServer.getLocalValue();
		assertEquals(CVMLong.ZERO, value1);

		// Update cursor directly
		maxNodeServer.getCursor().set(CVMLong.create(42));

		// getLocalValue should reflect the change
		AInteger value2 = maxNodeServer.getLocalValue();
		assertEquals(CVMLong.create(42), value2);
	}

	/**
	 * Test connecting to NodeServer with ConvexRemote
	 */
	@Test
	public void testConvexRemoteConnection() throws IOException, InterruptedException, java.util.concurrent.TimeoutException {
		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store, null);
		
		// Launch the server
		maxNodeServer.launch();
		assertTrue(maxNodeServer.isRunning());
		
		// Get the server address
		InetSocketAddress serverAddress = maxNodeServer.getHostAddress();
		assertNotNull(serverAddress, "Server should have a host address after launch");
		
		// Connect with ConvexRemote
		ConvexRemote convex = ConvexRemote.connect(serverAddress);
		assertNotNull(convex, "ConvexRemote connection should be created");
		assertTrue(convex.isConnected(), "ConvexRemote should be connected");
		
		// Clean up
		convex.close();
		assertFalse(convex.isConnected(), "ConvexRemote should be disconnected after close");
	}
	
	/**
	 * Test that a PING request returns a result
	 */
	@Test
	public void testPingRequest() throws IOException, InterruptedException, java.util.concurrent.TimeoutException, java.util.concurrent.ExecutionException {
		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store, null);
		
		// Launch the server
		maxNodeServer.launch();
		assertTrue(maxNodeServer.isRunning());
		
		// Get the server address
		InetSocketAddress serverAddress = maxNodeServer.getHostAddress();
		assertNotNull(serverAddress, "Server should have a host address after launch");
		
		// Connect with ConvexRemote
		ConvexRemote convex = ConvexRemote.connect(serverAddress);
		assertNotNull(convex, "ConvexRemote connection should be created");
		
		try {
			// Create a PING message with ID 1
			// Payload format: [:PING id]
			CVMLong pingId = CVMLong.create(1);
			AVector<?> pingPayload = Vectors.create(MessageTag.PING, pingId);
			Message pingMessage = Message.create(MessageType.PING, pingPayload);
			
			// Send PING message and wait for result
			CompletableFuture<Result> resultFuture = convex.message(pingMessage);
			Result result = resultFuture.get(5, TimeUnit.SECONDS);
			
			// Verify result
			assertNotNull(result, "PING should return a result");
			assertEquals(pingId, result.getID(), "Result ID should match PING ID");
			assertFalse(result.isError(), "PING should succeed");
			assertNotNull(result.getValue(), "PING result should have a value");
		} finally {
			convex.close();
		}
	}
	
	/**
	 * Test that a LATTICE_QUERY request with an empty path returns a valid lattice value
	 */
	@Test
	public void testLatticeQueryEmptyPath() throws IOException, InterruptedException, java.util.concurrent.TimeoutException, java.util.concurrent.ExecutionException {
		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store, null);
		
		// Set an initial value
		maxNodeServer.getCursor().set(CVMLong.create(42));
		
		// Launch the server
		maxNodeServer.launch();
		assertTrue(maxNodeServer.isRunning());
		
		// Get the server address
		InetSocketAddress serverAddress = maxNodeServer.getHostAddress();
		assertNotNull(serverAddress, "Server should have a host address after launch");
		
		// Connect with ConvexRemote
		ConvexRemote convex = ConvexRemote.connect(serverAddress);
		assertNotNull(convex, "ConvexRemote connection should be created");
		
		try {
			// Create a LATTICE_QUERY message with empty path
			// Payload format: [:LQ id []]
			CVMLong queryId = CVMLong.create(2);
			AVector<ACell> emptyPath = Vectors.empty();
			AVector<?> queryPayload = Vectors.create(MessageTag.LATTICE_QUERY, queryId, emptyPath);
			Message queryMessage = Message.create(MessageType.LATTICE_QUERY, queryPayload);
			
			// Send LATTICE_QUERY message and wait for result
			CompletableFuture<Result> resultFuture = convex.message(queryMessage);
			Result result = resultFuture.get(5, TimeUnit.SECONDS);
			
			// Verify result
			assertNotNull(result, "LATTICE_QUERY should return a result");
			assertEquals(queryId, result.getID(), "Result ID should match query ID");
			assertFalse(result.isError(), "LATTICE_QUERY should succeed");
			
			// Verify the returned value is a valid lattice value (should be 42)
			ACell value = result.getValue();
			assertNotNull(value, "LATTICE_QUERY result should have a value");
			assertEquals(CVMLong.create(42), value, "LATTICE_QUERY should return the current lattice value");
		} finally {
			convex.close();
		}
	}
	
	/**
	 * Test that Convex.acquire can retrieve a lattice structure via repeated DATA_REQUESTs.
	 *
	 * This verifies that NodeServer's DATA_REQUEST handling is compatible with the peer
	 * protocol and can serve a complete lattice value to a remote client store.
	 */
	@Test
	public void testAcquireLatticeStructureViaDataRequests() throws IOException, InterruptedException, java.util.concurrent.TimeoutException, java.util.concurrent.ExecutionException {
		// Use a SetLattice-backed NodeServer with the shared test store
		ALattice<ASet<ACell>> lattice = SetLattice.create();
		setNodeServer = new NodeServer<>(lattice, store, null);
		
		// Create a large lattice value (10,000 distinct elements) so that it spans multiple branches
		ASet<ACell> latticeValue = Sets.empty();
		for (int i = 0; i < 10_000; i++) {
			latticeValue = latticeValue.conj(CVMLong.create(i));
		}
		
		// Persist the lattice value into the NodeServer's store so it can be served via DATA_REQUEST
		latticeValue = Cells.store(latticeValue, store);
		Hash valueHash = Cells.getHash(latticeValue);
		
		// Launch the NodeServer
		setNodeServer.launch();
		assertTrue(setNodeServer.isRunning());
		
		// Connect a remote Convex client to the NodeServer
		InetSocketAddress serverAddress = setNodeServer.getHostAddress();
		assertNotNull(serverAddress, "Server should have a host address after launch");
		
		ConvexRemote convex = ConvexRemote.connect(serverAddress);
		
		try {
			// Acquire the lattice structure into the client store. This will issue
			// one or more DATA_REQUEST messages that NodeServer must handle correctly.
			CompletableFuture<ACell> future = convex.acquire(valueHash);
			ACell acquired = future.get(10, TimeUnit.SECONDS);
			
			assertNotNull(acquired, "Acquired lattice value should not be null");
			
			// Verify that we acquired a large lattice structure (10,000 elements)
			assertTrue(acquired instanceof ASet, "Acquired value should be a set");
			ASet<?> acquiredSet = (ASet<?>) acquired;
			assertEquals(10_000L, acquiredSet.count(), "Acquired set should contain 10,000 elements");
			assertTrue(acquiredSet.contains(CVMLong.create(0)));
			assertTrue(acquiredSet.contains(CVMLong.create(9_999)));
		} finally {
			convex.close();
		}
	}
}

