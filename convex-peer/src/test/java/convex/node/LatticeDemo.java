package convex.node;

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
 * <h2>What This Demo Shows</h2>
 * This demonstrates how Convex lattices automatically synchronize across a decentralized network:
 * <ul>
 *   <li>Multiple independent nodes can share the same lattice data structure</li>
 *   <li>Updates made on one node automatically propagate to all connected peers</li>
 *   <li>All nodes eventually converge to the same state (eventual consistency)</li>
 *   <li>No central coordinator needed - fully peer-to-peer</li>
 * </ul>
 *
 * <h2>How It Works</h2>
 * <ol>
 *   <li>Each NodeServer has a LatticePropagator that watches for local changes</li>
 *   <li>When a change is detected, it broadcasts a delta (only the new data) to peers</li>
 *   <li>Peers receive the delta and merge it into their local lattice copy</li>
 *   <li>Lattice merge is mathematically guaranteed to converge (CRDT properties)</li>
 *   <li>Missing data is automatically requested and filled in as needed</li>
 * </ol>
 *
 * <h2>Key Concepts</h2>
 * <ul>
 *   <li><b>Lattice</b>: A data structure with a merge operation that is commutative, associative, and idempotent</li>
 *   <li><b>Delta Broadcast</b>: Only new/changed data is sent, not the entire state</li>
 *   <li><b>Convergence</b>: All nodes reach the same final state regardless of message ordering</li>
 *   <li><b>Self-Healing</b>: Missing data is detected and automatically fetched from peers</li>
 * </ul>
 */
public class LatticeDemo {

	// Configuration: How many nodes and operations to run
	private static final int NUM_NODES = 3;      // Number of independent nodes in the network
	private static final int MERGES = 1000;       // Number of merge batches to perform
	private static final int MODS = 1000;         // Number of modifications per merge batch
	private static final int BASE_PORT = 19700;  // Starting port number for nodes

	public static void main(String[] args) throws Exception {
		System.out.println("=== Lattice Synchronization Demo ===");
		System.out.println("Creating " + NUM_NODES + " nodes with automatic propagation...\n");

		// Collections to hold our nodes and their storage
		List<NodeServer<?>> servers = new ArrayList<>();
		List<AStore> stores = new ArrayList<>();

		// Use Lattice.ROOT - the standard Convex lattice with :data keyword for storage
		ALattice<?> lattice = Lattice.ROOT;

		try {
			// STEP 1: Create and launch independent nodes
			// Each node has its own memory store and listens on a different port
			for (int i = 0; i < NUM_NODES; i++) {
				// Create an in-memory store for this node's data
				AStore store = new MemoryStore();
				stores.add(store);

				// Create and launch a NodeServer
				// - Takes the lattice definition (what data structure to use)
				// - Takes a store (where to persist data)
				// - Takes a port number (how other nodes can connect to it)
				NodeServer<?> server = new NodeServer<>(lattice, store, NodeConfig.port(BASE_PORT + i));
				server.launch();  // This automatically starts the LatticePropagator
				servers.add(server);

				System.out.println("Node " + (i + 1) + " started on port " + (BASE_PORT + i));
			}

			System.out.println("\nEstablishing peer connections (full mesh)...");

			// STEP 2: Connect all nodes to each other (full mesh topology)
			// Each node connects to every other node as a peer
			// This creates a fully decentralized network with no single point of failure
			for (int i = 0; i < NUM_NODES; i++) {
				NodeServer<?> server = servers.get(i);
				for (int j = 0; j < NUM_NODES; j++) {
					if (i != j) {  // Don't connect to self
						// Create a connection to the remote node
						InetSocketAddress peerAddress = new InetSocketAddress("localhost", BASE_PORT + j);
						Convex peer = ConvexRemote.connect(peerAddress);

						// Add this peer to the node's peer list
						// Now this node can send broadcasts to this peer
						server.getPropagator().addPeer(peer);
					}
				}
				System.out.println("Node " + (i + 1) + " connected to " + (NUM_NODES - 1) + " peers");
			}

			System.out.println("\n=== Starting Merge Operations ===");
			System.out.println("Performing " + MERGES + " merges with " + MODS + " modifications each on Node 1...\n");

			// Use fixed random seed so the demo is reproducible
			Random random = new Random(12345);

			// The :data keyword is where we store our key-value pairs in the lattice
			// Think of it like a path: lattice[:data][hash] = value
			Keyword dataKeyword = Keyword.intern("data");

			// We'll make all our changes on node 1
			// The propagator will automatically broadcast them to nodes 2 and 3
			NodeServer<?> node1 = servers.get(0);

			long startTime = System.currentTimeMillis();

			// STEP 3: Perform many modifications on Node 1
			// This simulates real-world usage where nodes are constantly updating their state
			for (int merge = 0; merge < MERGES; merge++) {
				// Get the current data index from node 1's lattice
				// The index is like a HashMap that maps Hash -> Value
				@SuppressWarnings("unchecked")
				Index<Hash, ACell> dataIndex = (Index<Hash, ACell>) node1.getCursor().get(dataKeyword);
				if (dataIndex == null) {
					// Start with empty index if nothing exists yet
					@SuppressWarnings("unchecked")
					Index<Hash, ACell> emptyIndex = (Index<Hash, ACell>) Index.EMPTY;
					dataIndex = emptyIndex;
				}

				// Add many new key-value pairs to the index
				// Each modification is a new entry: Hash(value) -> value
				for (int mod = 0; mod < MODS; mod++) {
					long value = random.nextLong(1000000);
					ACell cellValue = CVMLong.create(value);
					Hash valueHash = Hash.get(cellValue);

					// assoc() creates a new index with the added entry
					// Convex data structures are immutable - we get a new version each time
					dataIndex = dataIndex.assoc(valueHash, cellValue);
				}

				// Update node 1's lattice with the new data
				// This triggers the LatticePropagator to:
				// 1. Detect the change
				// 2. Compute what's new (delta)
				// 3. Broadcast the delta to all connected peers
				// All of this happens automatically in the background!
				node1.getCursor().assoc(dataKeyword, dataIndex);
				node1.getPropagator().triggerBroadcast(node1.getLocalValue());

				if ((merge + 1) % 10 == 0) {
					System.out.println("Completed " + (merge + 1) + " merges (" + ((merge + 1) * MODS) + " total modifications)");
				}
			}

			long mergeTime = System.currentTimeMillis() - startTime;
			System.out.println("\nMerge operations completed in " + mergeTime + "ms");

			System.out.println("\n=== Synchronizing All Nodes ===");

			// STEP 4: Ensure all nodes have caught up
			// The automatic broadcasts happen in the background, but we can
			// explicitly sync to ensure everything is caught up right now
			long syncStart = System.currentTimeMillis();
			for (int i = 0; i < NUM_NODES; i++) {
				NodeServer<?> server = servers.get(i);

				// pull() queries all peers for their current state and merges it locally
				// This ensures this node has the latest data from everyone
				// In production, this happens automatically - we're just being explicit here
				boolean pullResult = server.pull();
				System.out.println("Node " + (i + 1) + " pull: " + (pullResult ? "SUCCESS" : "FAILED"));
			}
			long syncTime = System.currentTimeMillis() - syncStart;
			System.out.println("Synchronization completed in " + syncTime + "ms");

			System.out.println("\n=== Verifying Convergence ===");

			// STEP 5: Check that all nodes have identical state
			// This is the key property of lattices - eventual consistency
			// No matter what order messages arrived, all nodes should have the same final state

			// Get the final lattice value from each node
			List<ACell> finalValues = new ArrayList<>();
			for (int i = 0; i < NUM_NODES; i++) {
				ACell value = servers.get(i).getLocalValue();
				finalValues.add(value);

				// Show the hash of each node's value
				// If hashes match, the entire data structure is identical
				System.out.println("Node " + (i + 1) + " final value hash: " + Hash.get(value));
			}

			// Compare all nodes to node 1
			// If all equal, convergence succeeded!
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

				// Show what we accomplished
				@SuppressWarnings("unchecked")
				Index<Hash, ACell> finalDataIndex = (Index<Hash, ACell>) node1.getCursor().get(dataKeyword);
				long entryCount = finalDataIndex != null ? finalDataIndex.count() : 0;

				System.out.println("\nStatistics:");
				System.out.println("  Total merges: " + MERGES);
				System.out.println("  Modifications per merge: " + MODS);
				System.out.println("  Total modifications: " + (MERGES * MODS));
				System.out.println("  Final lattice entries: " + entryCount);
				System.out.println("    (Note: Less than " + (MERGES * MODS) + " due to random hash collisions)");
				System.out.println("  Merge time: " + mergeTime + "ms");
				System.out.println("  Sync time: " + syncTime + "ms");
				System.out.println("  Total time: " + (mergeTime + syncTime) + "ms");

				// Show how efficient the propagation was
				// The key insight: We made 10,000 changes but only needed a handful of broadcasts!
				// This is because the propagator intelligently batches and only sends deltas
				System.out.println("\nPropagator Statistics (how automatic sync worked):");
				for (int i = 0; i < NUM_NODES; i++) {
					LatticePropagator propagator = servers.get(i).getPropagator();
					System.out.println("  Node " + (i + 1) + ": " +
						propagator.getBroadcastCount() + " delta broadcasts, " +
						propagator.getRootSyncCount() + " root syncs");
				}
				System.out.println("\n  Delta broadcasts = sending only new data to peers");
				System.out.println("  Root syncs = sending just the top hash to check if peers need updates");
			} else {
				System.out.println("\n✗ FAILURE: Nodes did not converge!");
				System.exit(1);
			}

		} finally {
			// CLEANUP: Always close resources properly
			System.out.println("\nShutting down nodes...");

			// Close all servers (stops network listeners and propagators)
			for (int i = 0; i < servers.size(); i++) {
				servers.get(i).close();
				System.out.println("Node " + (i + 1) + " shutdown complete");
			}

			// Close all stores (releases any resources held by storage)
			for (int i = 0; i < stores.size(); i++) {
				stores.get(i).close();
			}
		}

		System.out.println("\n=== Demo Complete ===");
	}
}
