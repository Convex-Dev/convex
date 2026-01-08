package convex.node;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.lattice.ALattice;
import convex.lattice.Lattice;
import convex.lattice.generic.MaxLattice;

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
			NodeServer<?> server = new NodeServer<>(commonLattice, store, port);
			nodeServers.add(server);
			
			// Launch the server
			server.launch();
			
			// Verify server is running
			assertTrue(server.isRunning(), "NodeServer " + i + " should be running after launch");
			assertNotNull(server.getHostAddress(), "NodeServer " + i + " should have a host address");
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
	 * Test that all NodeServers share the same lattice instance
	 */
	@Test
	public void testSharedLattice() {
		ALattice<?> firstLattice = nodeServers.get(0).getLattice();
		
		for (int i = 1; i < nodeServers.size(); i++) {
			ALattice<?> serverLattice = nodeServers.get(i).getLattice();
			assertTrue(serverLattice == firstLattice,
					"NodeServer " + i + " should share the same lattice instance as NodeServer 0");
			assertTrue(serverLattice == commonLattice,
					"NodeServer " + i + " should use the common lattice instance");
		}
	}
}

