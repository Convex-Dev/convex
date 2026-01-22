package convex.node;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.prim.CVMLong;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.lattice.ALattice;
import convex.lattice.Lattice;

/**
 * Demo application showing automatic lattice synchronization across multiple nodes.
 *
 * This demo:
 * - Creates 3 NodeServers with automatic propagation
 * - Performs multiple merge operations on node 1
 * - Verifies all nodes converge to the same lattice value
 */
public class LatticeDemo {

	private static final int NUM_NODES = 3;
	private static final int MERGES = 100;
	private static final int MODS = 100;
	private static final int BASE_PORT = 19700;

	public static void main(String[] args) throws Exception {
		System.out.println("=== Lattice Synchronization Demo ===");
		System.out.println("Creating " + NUM_NODES + " nodes with automatic propagation...\n");

		// Create nodes and stores
		List<NodeServer<?>> servers = new ArrayList<>();
		List<AStore> stores = new ArrayList<>();
		ALattice<?> lattice = Lattice.ROOT;

		try {
			// Launch all servers
			for (int i = 0; i < NUM_NODES; i++) {
				AStore store = new MemoryStore();
				stores.add(store);

				NodeServer<?> server = new NodeServer<>(lattice, store, BASE_PORT + i);
				server.launch();
				servers.add(server);

				System.out.println("Node " + (i + 1) + " started on port " + (BASE_PORT + i));
			}

			System.out.println("\nEstablishing peer connections (full mesh)...");

			// Establish full mesh peer connections
			for (int i = 0; i < NUM_NODES; i++) {
				NodeServer<?> server = servers.get(i);
				for (int j = 0; j < NUM_NODES; j++) {
					if (i != j) {
						InetSocketAddress peerAddress = new InetSocketAddress("localhost", BASE_PORT + j);
						Convex peer = ConvexRemote.connect(peerAddress);
						server.addPeer(peer);
					}
				}
				System.out.println("Node " + (i + 1) + " connected to " + (NUM_NODES - 1) + " peers");
			}

			System.out.println("\n=== Starting Merge Operations ===");
			System.out.println("Performing " + MERGES + " merges with " + MODS + " modifications each on Node 1...\n");

			Random random = new Random(12345); // Fixed seed for reproducibility
			Keyword dataKeyword = Keyword.intern("data");
			NodeServer<?> node1 = servers.get(0);

			long startTime = System.currentTimeMillis();

			// Perform merge operations
			for (int merge = 0; merge < MERGES; merge++) {
				// Get current lattice value from node 1
				@SuppressWarnings("unchecked")
				Index<Hash, ACell> dataIndex = (Index<Hash, ACell>) node1.getCursor().get(dataKeyword);
				if (dataIndex == null) {
					@SuppressWarnings("unchecked")
					Index<Hash, ACell> emptyIndex = (Index<Hash, ACell>) Index.EMPTY;
					dataIndex = emptyIndex;
				}

				// Make modifications
				for (int mod = 0; mod < MODS; mod++) {
					long value = random.nextLong(1000000);
					ACell cellValue = CVMLong.create(value);
					Hash valueHash = Hash.get(cellValue);
					dataIndex = dataIndex.assoc(valueHash, cellValue);
				}

				// Update node 1 with modified value (triggers automatic broadcast)
				node1.updateLocalPath(dataIndex, dataKeyword);

				if ((merge + 1) % 10 == 0) {
					System.out.println("Completed " + (merge + 1) + " merges (" + ((merge + 1) * MODS) + " total modifications)");
				}
			}

			long mergeTime = System.currentTimeMillis() - startTime;
			System.out.println("\nMerge operations completed in " + mergeTime + "ms");

			System.out.println("\n=== Synchronizing All Nodes ===");

			// Sync all nodes to ensure convergence
			long syncStart = System.currentTimeMillis();
			for (int i = 0; i < NUM_NODES; i++) {
				NodeServer<?> server = servers.get(i);
				boolean syncResult = server.sync();
				System.out.println("Node " + (i + 1) + " sync: " + (syncResult ? "SUCCESS" : "FAILED"));
			}
			long syncTime = System.currentTimeMillis() - syncStart;
			System.out.println("Synchronization completed in " + syncTime + "ms");

			System.out.println("\n=== Verifying Convergence ===");

			// Get final values from all nodes
			List<ACell> finalValues = new ArrayList<>();
			for (int i = 0; i < NUM_NODES; i++) {
				ACell value = servers.get(i).getLocalValue();
				finalValues.add(value);
				System.out.println("Node " + (i + 1) + " final value hash: " + Hash.get(value));
			}

			// Verify all nodes have the same value
			boolean allEqual = true;
			ACell reference = finalValues.get(0);
			for (int i = 1; i < finalValues.size(); i++) {
				if (!reference.equals(finalValues.get(i))) {
					allEqual = false;
					System.out.println("\nERROR: Node " + (i + 1) + " has different value than Node 1!");
				}
			}

			if (allEqual) {
				System.out.println("\n✓ SUCCESS: All nodes converged to the same lattice value!");

				// Calculate statistics
				@SuppressWarnings("unchecked")
				Index<Hash, ACell> finalDataIndex = (Index<Hash, ACell>) node1.getCursor().get(dataKeyword);
				long entryCount = finalDataIndex != null ? finalDataIndex.count() : 0;

				System.out.println("\nStatistics:");
				System.out.println("  Total merges: " + MERGES);
				System.out.println("  Modifications per merge: " + MODS);
				System.out.println("  Total modifications: " + (MERGES * MODS));
				System.out.println("  Final lattice entries: " + entryCount);
				System.out.println("  Merge time: " + mergeTime + "ms");
				System.out.println("  Sync time: " + syncTime + "ms");
				System.out.println("  Total time: " + (mergeTime + syncTime) + "ms");

				// Show propagator statistics
				System.out.println("\nPropagator Statistics:");
				for (int i = 0; i < NUM_NODES; i++) {
					LatticePropagator<?> propagator = servers.get(i).getPropagator();
					System.out.println("  Node " + (i + 1) + ": " +
						propagator.getBroadcastCount() + " delta broadcasts, " +
						propagator.getRootSyncCount() + " root syncs");
				}
			} else {
				System.out.println("\n✗ FAILURE: Nodes did not converge!");
				System.exit(1);
			}

		} finally {
			// Cleanup
			System.out.println("\nShutting down nodes...");
			for (int i = 0; i < servers.size(); i++) {
				try {
					servers.get(i).close();
					System.out.println("Node " + (i + 1) + " shutdown complete");
				} catch (Exception e) {
					System.err.println("Error closing node " + (i + 1) + ": " + e.getMessage());
				}
			}

			for (int i = 0; i < stores.size(); i++) {
				try {
					stores.get(i).close();
				} catch (Exception e) {
					System.err.println("Error closing store " + (i + 1) + ": " + e.getMessage());
				}
			}
		}

		System.out.println("\n=== Demo Complete ===");
	}
}
