package convex.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.message.MessageType;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.lattice.ALattice;
import convex.lattice.Lattice;

/**
 * Tests for a network of NodeServer instances.
 * 
 * This test class uses one-instance-per-class execution semantics, creating
 * a network of three NodeServers in @BeforeAll and shutting them down in @AfterAll.
 * All servers share a common Lattice instance.
 */
@TestInstance(Lifecycle.PER_CLASS)
public class NodeNetworkTest {

	/**
	 * Common lattice instance shared by all NodeServers in the network
	 */
	private ALattice<?> commonLattice;
	
	/**
	 * List of NodeServer instances in the network
	 */
	private List<NodeServer<?>> nodeServers;
	
	/**
	 * List of stores for each NodeServer
	 */
	private List<AStore> stores;
	
	/**
	 * Number of NodeServers in the network
	 */
	private static final int NETWORK_SIZE = 3;
	
	/**
	 * Base port for the first NodeServer (others will use sequential ports)
	 */
	private static final int BASE_PORT = 19000;

	/**
	 * Sets up the network of NodeServers before all tests run.
	 * Creates three NodeServers, each with its own store, all sharing
	 * the same Lattice instance.
	 * 
	 * @throws IOException If an IO error occurs during server launch
	 * @throws InterruptedException If the operation is interrupted
	 */
	@BeforeAll
	public void setUpNetwork() throws IOException, InterruptedException {
		// Create common lattice instance
		commonLattice = Lattice.ROOT;
		
		// Initialize lists
		nodeServers = new ArrayList<>(NETWORK_SIZE);
		stores = new ArrayList<>(NETWORK_SIZE);
		
		// Create and launch three NodeServers
		for (int i = 0; i < NETWORK_SIZE; i++) {
			// Create a store for this NodeServer
			AStore store = new MemoryStore();
			stores.add(store);
			
			// Create NodeServer with the common lattice
			Integer port = BASE_PORT + i;
			NodeServer<?> server = new NodeServer<>(commonLattice, store, NodeConfig.port(port));
			nodeServers.add(server);
			
			// Launch the server
			server.launch();
			
			// Verify server is running
			assertTrue(server.isRunning(), "NodeServer " + i + " should be running after launch");
			assertNotNull(server.getHostAddress(), "NodeServer " + i + " should have a host address");
		}
		
		// Set up peer connections: make all servers peers of each other
		// Create Convex connections between all servers
		for (int i = 0; i < NETWORK_SIZE; i++) {
			NodeServer<?> server = nodeServers.get(i);
			
			// Add all other servers as peers using Convex connections
			for (int j = 0; j < NETWORK_SIZE; j++) {
				if (i != j) {
					NodeServer<?> otherServer = nodeServers.get(j);
					InetSocketAddress otherAddress = otherServer.getHostAddress();
					try {
						Convex peerConnection = ConvexRemote.connect(otherAddress);
						AccountKey peerKey = AKeyPair.generate().getAccountKey();
						server.getPropagator().addPeer(peerKey, peerConnection);
					} catch (Exception e) {
						throw new RuntimeException("Failed to create peer connection from server " + i + " to server " + j, e);
					}
				}
			}
		}
	}

	/**
	 * Shuts down all NodeServers in the network after all tests complete.
	 * 
	 * @throws IOException If an IO error occurs during shutdown
	 */
	@AfterAll
	public void tearDownNetwork() throws IOException {
		// Close all NodeServers
		for (NodeServer<?> server : nodeServers) {
			server.close();
		}
		nodeServers.clear();
		
		// Close all stores
		for (AStore store : stores) {
			store.close();
		}
		stores.clear();
	}

	/**
	 * Test getting NodeServer instances by index
	 */
	@Test
	public void testGetNodeServer() {
		for (int i = 0; i < NETWORK_SIZE; i++) {
			NodeServer<?> server = nodeServers.get(i);
			assertNotNull(server, "NodeServer at index " + i + " should not be null");
			assertTrue(server.isRunning(), "NodeServer at index " + i + " should be running");
		}
	}
	
	/**
	 * Test syncing lattice values between nodes.
	 * 
	 * Inserts a new lattice data value in server 0 at the :data path with the value's hash as key,
	 * calls sync on the last server to sync with server 0,
	 * and verifies the last server has the new data value.
	 */
	@Test
	public void testSync() throws Exception {
		// Get server 0 and last server
		NodeServer<?> server0 = nodeServers.get(0);
		NodeServer<?> lastServer = nodeServers.get(NETWORK_SIZE - 1);
		
		// Get the keyword for :data
		Keyword dataKeyword = Keyword.intern("data");
		
		// Create a test value and get its hash
		ACell testValue = CVMLong.create(12345);
		Hash valueHash = Hash.get(testValue);
		
		// Get the current Index at :data path (or create empty one if null)
		@SuppressWarnings("unchecked")
		Index<Hash, ACell> dataIndex = (Index<Hash, ACell>) server0.getCursor().get(dataKeyword);
		if (dataIndex == null) {
			@SuppressWarnings("unchecked")
			Index<Hash, ACell> emptyIndex = (Index<Hash, ACell>) Index.EMPTY;
			dataIndex = emptyIndex;
		}
		
		// Store the value in the Index using its hash as the key
		Index<Hash, ACell> updatedDataIndex = dataIndex.assoc(valueHash, testValue);
		
		// Update the :data path with the updated Index
		server0.getCursor().assoc(dataKeyword, updatedDataIndex);

		// Sync so the propagator has the value for LATTICE_QUERY responses
		server0.getCursor().sync();
		Thread.sleep(100);

		// Create the query path [:data valueHash] for reuse
		AVector<ACell> queryPath = Vectors.create(dataKeyword, valueHash);
		
		// Verify server 0 has the value at [:data valueHash] by sending a LATTICE_QUERY via ConvexRemote
		InetSocketAddress server0Address = server0.getHostAddress();
		assertNotNull(server0Address, "Server 0 should have a host address");
		ConvexRemote convex0 = ConvexRemote.connect(server0Address);
		assertNotNull(convex0, "ConvexRemote connection to server 0 should be created");
		
		try {
			// Create a LATTICE_QUERY message with path [:data valueHash]
			// Payload format: [:LQ id [:data valueHash]]
			CVMLong queryId0 = CVMLong.create(100);
			AVector<?> queryPayload0 = Vectors.create(MessageTag.LATTICE_QUERY, queryId0, queryPath);
			Message queryMessage0 = Message.create(MessageType.LATTICE_QUERY, queryPayload0);
			
			// Send LATTICE_QUERY message and wait for result
			CompletableFuture<Result> resultFuture0 = convex0.message(queryMessage0);
			Result result0 = resultFuture0.get(5, TimeUnit.SECONDS);
			
			// Verify result
			assertNotNull(result0, "LATTICE_QUERY should return a result");
			assertEquals(queryId0, result0.getID(), "Result ID should match query ID");
			
			// Verify the returned value matches the test value
			ACell valueAtServer0 = result0.getValue();
			assertEquals(testValue, valueAtServer0, "Server 0 should have the test value at [:data valueHash] path");
		} finally {
			convex0.close();
		}
		
		// Peer connections should already be established by setUpNetwork
		
		// Call sync on the last server to sync with server 0
		assertTrue(lastServer.pull(), "Pull should succeed");
		
		// Verify the last server has the new data value at [:data valueHash] path via LATTICE_QUERY
		InetSocketAddress lastServerAddress = lastServer.getHostAddress();
		assertNotNull(lastServerAddress, "Last server should have a host address");
		ConvexRemote convexLast = ConvexRemote.connect(lastServerAddress);
		assertNotNull(convexLast, "ConvexRemote connection to last server should be created");
		
		try {
			// Create a LATTICE_QUERY message with path [:data valueHash]
			CVMLong queryIdLast = CVMLong.create(200);
			AVector<?> queryPayloadLast = Vectors.create(MessageTag.LATTICE_QUERY, queryIdLast, queryPath);
			Message queryMessageLast = Message.create(MessageType.LATTICE_QUERY, queryPayloadLast);
			
			// Send LATTICE_QUERY message and wait for result
			CompletableFuture<Result> resultFutureLast = convexLast.message(queryMessageLast);
			Result resultLast = resultFutureLast.get(5, TimeUnit.SECONDS);
			
			// Verify result
			assertNotNull(resultLast, "LATTICE_QUERY should return a result");
			assertEquals(queryIdLast, resultLast.getID(), "Result ID should match query ID");
			assertFalse(resultLast.isError(), "LATTICE_QUERY should succeed");
			
			// Verify the returned value matches the test value
			ACell valueAtLastServer = resultLast.getValue();
			assertNotNull(valueAtLastServer, "LATTICE_QUERY result should have a value");
			assertEquals(testValue, valueAtLastServer, "Last server should have the synced value at [:data valueHash] path");
		} finally {
			convexLast.close();
		}
	}
}

