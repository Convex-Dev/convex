package convex.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.lattice.ALattice;
import convex.core.data.ACell;
import convex.lattice.Lattice;

/**
 * Class-level lattice network test that sets up a small network of NodeServer
 * instances backed by the base {@link Lattice#ROOT} lattice.
 *
 * The network is created once per test class and torn down once after all tests
 * complete.
 */
@TestInstance(Lifecycle.PER_CLASS)
public class LatticeNetworkTest {

	/**
	 * Common base lattice instance shared by all NodeServers in the network.
	 */
	private ALattice<?> commonLattice;

	/**
	 * List of NodeServer instances in the network.
	 */
	private List<NodeServer<?>> nodeServers;

	/**
	 * List of stores for each NodeServer.
	 */
	private List<AStore> stores;

	/**
	 * Number of NodeServers in the network.
	 */
	private static final int NETWORK_SIZE = 3;

	/**
	 * Base port for the first NodeServer (others will use sequential ports).
	 */
	private static final int BASE_PORT = 19500;

	/**
	 * Sets up a network of NodeServers backed by {@link Lattice#ROOT} before all
	 * tests run. Creates {@value #NETWORK_SIZE} NodeServers, each with its own
	 * store, all sharing the same lattice instance, and establishes Convex peer
	 * connections between all nodes.
	 *
	 * @throws IOException          If an IO error occurs during server launch
	 * @throws InterruptedException If the operation is interrupted
	 */
	@BeforeAll
	public void setUpNetwork() throws IOException, InterruptedException {
		// Use the base lattice
		commonLattice = Lattice.ROOT;

		// Initialize lists
		nodeServers = new ArrayList<>(NETWORK_SIZE);
		stores = new ArrayList<>(NETWORK_SIZE);

		// Create and launch NodeServers
		for (int i = 0; i < NETWORK_SIZE; i++) {
			AStore store = new MemoryStore();
			stores.add(store);

			Integer port = BASE_PORT + i;
			NodeServer<?> server = new NodeServer<>(commonLattice, store, NodeConfig.port(port));
			nodeServers.add(server);

			server.launch();

			assertTrue(server.isRunning(), "NodeServer " + i + " should be running after launch");
			assertNotNull(server.getHostAddress(), "NodeServer " + i + " should have a host address");
		}

		// Establish Convex peer connections between all servers
		for (int i = 0; i < NETWORK_SIZE; i++) {
			NodeServer<?> server = nodeServers.get(i);

			for (int j = 0; j < NETWORK_SIZE; j++) {
				if (i == j)
					continue;

				NodeServer<?> otherServer = nodeServers.get(j);
				InetSocketAddress otherAddress = otherServer.getHostAddress();
				assertNotNull(otherAddress, "Other server " + j + " should have a host address");

				try {
					AccountKey peerKey = AKeyPair.generate().getAccountKey();
					Convex peerConnection = ConvexRemote.connect(otherAddress);
					server.getPropagator().addPeer(peerKey, peerConnection);
				} catch (Exception e) {
					throw new RuntimeException(
							"Failed to create Convex peer connection from server " + i + " to server " + j, e);
				}
			}
		}
	}

	/**
	 * Tears down the network after all tests complete by closing all NodeServers
	 * and stores.
	 *
	 * @throws IOException If an IO error occurs during shutdown
	 */
	@AfterAll
	public void tearDownNetwork() throws IOException {
		for (NodeServer<?> server : nodeServers) {
			server.close();
		}
		nodeServers.clear();

		for (AStore store : stores) {
			store.close();
		}
		stores.clear();
	}

	/**
	 * Syncs the entire network, ensuring all nodes merge each other's values at least once.
	 * 
	 * This method performs a full network sync by:
	 * 1. Having each node sync with all its connected peers
	 * 2. Waiting for all sync operations to complete
	 * 3. Ensuring that every node has merged with every other node at least once
	 * 
	 * @throws InterruptedException If the operation is interrupted
	 * @throws ExecutionException If a sync operation fails
	 * @throws TimeoutException If a sync operation times out
	 */
	public void syncNetwork() throws InterruptedException, ExecutionException, TimeoutException {
		// Collect all sync futures from all nodes
		List<CompletableFuture<?>> allSyncFutures = new ArrayList<>();
		
		// For each node, sync with all its peers
		for (int i = 0; i < NETWORK_SIZE; i++) {
			NodeServer<?> server = nodeServers.get(i);
			Set<Convex> peers = server.getPropagator().getPeers();
			
			// For each peer, create a pull future
			for (Convex peer : peers) {
				if (peer != null && peer.isConnected()) {
					// Use the pull method which returns a CompletableFuture
					CompletableFuture<?> pullFuture = server.pull(peer);
					allSyncFutures.add(pullFuture);
				}
			}
		}
		
		// Wait for all sync operations to complete
		CompletableFuture<Void> allSyncs = CompletableFuture.allOf(
			allSyncFutures.toArray(new CompletableFuture[0])
		);
		
		// Wait with a reasonable timeout (30 seconds should be enough for a small network)
		allSyncs.get(30, TimeUnit.SECONDS);
	}

	/**
	 * Tests that after syncing the network, all servers have the same lattice value.
	 * 
	 * @throws InterruptedException If the operation is interrupted
	 * @throws ExecutionException If a sync operation fails
	 * @throws TimeoutException If a sync operation times out
	 */
	@Test
	public void testSyncNetwork() throws InterruptedException, ExecutionException, TimeoutException {
		// Sync the entire network
		syncNetwork();
		
		// Get the lattice value from the first server as the reference
		NodeServer<?> firstServer = nodeServers.get(0);
		ACell referenceValue = firstServer.getLocalValue();
		assertNotNull(referenceValue, "First server should have a lattice value");
		
		// Verify all other servers have the same lattice value
		for (int i = 1; i < NETWORK_SIZE; i++) {
			NodeServer<?> server = nodeServers.get(i);
			ACell serverValue = server.getLocalValue();
			assertNotNull(serverValue, "Server " + i + " should have a lattice value");
			assertEquals(referenceValue, serverValue, 
				"Server " + i + " should have the same lattice value as server 0 after sync");
		}
	}
}

