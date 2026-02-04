package convex.node;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.core.util.Shutdown;
import convex.lattice.Lattice;
import convex.lattice.kv.KVDatabase;

/**
 * Demo showing KV store replication across a network of NodeServers.
 *
 * <p>Each node creates a signed KV database and publishes it into the global
 * lattice at {@code :kv / "shared" / <owner-key>}. The LatticePropagator
 * automatically broadcasts changes to peers, and lattice merge (OwnerLattice +
 * KVStoreLattice) combines signed replicas from all nodes.
 *
 * <p>After sync, each node reads back the merged owner map and absorbs remote
 * data into its local KVDatabase via {@code mergeReplicas()}.
 *
 * <p>Run with:
 * <pre>
 * mvn -pl convex-peer exec:java \
 *   -Dexec.mainClass=convex.node.KVReplicationDemo \
 *   -Dexec.classpathScope=test -q
 * </pre>
 */
public class KVReplicationDemo {

	private static final int NUM_NODES = 3;
	private static final int BASE_PORT = 19800;
	private static final String DB_NAME = "shared";

	public static void main(String[] args) throws Exception {
		System.out.println("=== KV Store Replication Demo ===\n");

		List<NodeServer<?>> servers = new ArrayList<>();
		List<AStore> stores = new ArrayList<>();
		List<KVDatabase> databases = new ArrayList<>();

		try {
			// --- Create and launch nodes ---
			for (int i = 0; i < NUM_NODES; i++) {
				AKeyPair kp = AKeyPair.generate();
				AStore store = new MemoryStore();
				stores.add(store);

				NodeServer<?> server = new NodeServer<>(Lattice.ROOT, store, BASE_PORT + i);
				server.launch();
				servers.add(server);

				KVDatabase db = KVDatabase.create(DB_NAME, kp, "node-" + i);
				databases.add(db);

				System.out.println("Node " + i + " started on port " + (BASE_PORT + i)
					+ "  key=" + kp.getAccountKey().toHexString(6) + "...");
			}

			// --- Connect full mesh ---
			System.out.println("\nConnecting peers...");
			for (int i = 0; i < NUM_NODES; i++) {
				for (int j = 0; j < NUM_NODES; j++) {
					if (i != j) {
						Convex peer = ConvexRemote.connect(
							new InetSocketAddress("localhost", BASE_PORT + j));
						servers.get(i).addPeer(peer);
					}
				}
			}

			// --- Each node writes different data ---
			System.out.println("\nWriting data on each node...\n");

			KVDatabase db0 = databases.get(0);
			db0.kv().set("user:alice", Strings.create("Alice"));
			db0.kv().set("user:bob", Strings.create("Bob"));
			db0.kv().sadd("tags", Strings.create("alpha"));
			db0.kv().incr("visits");
			System.out.println("Node 0: user:alice, user:bob, tags={alpha}, visits++");

			KVDatabase db1 = databases.get(1);
			db1.kv().set("user:charlie", Strings.create("Charlie"));
			db1.kv().sadd("tags", Strings.create("beta"));
			db1.kv().sadd("tags", Strings.create("gamma"));
			db1.kv().incr("visits");
			System.out.println("Node 1: user:charlie, tags={beta,gamma}, visits++");

			KVDatabase db2 = databases.get(2);
			db2.kv().set("user:dave", Strings.create("Dave"));
			db2.kv().incrby("visits", 5);
			System.out.println("Node 2: user:dave, visits+=5");

			// --- Publish signed replicas into the lattice ---
			System.out.println("\nPublishing signed replicas to lattice...");
			AString dbName = Strings.create(DB_NAME);
			for (int i = 0; i < NUM_NODES; i++) {
				@SuppressWarnings("unchecked")
				AHashMap<ACell, ACell> replica =
					(AHashMap<ACell, ACell>)(AHashMap<?,?>) databases.get(i).exportReplica();
				AHashMap<ACell, ACell> kvMap = Maps.of(dbName, replica);
				servers.get(i).updateLocalPath(kvMap, Keywords.KV);
			}

			// --- Sync network ---
			System.out.println("Syncing network...");
			for (NodeServer<?> server : servers) server.sync();
			Thread.sleep(500);
			for (NodeServer<?> server : servers) server.sync();

			// --- Verify lattice convergence ---
			System.out.println("\n=== Lattice Convergence ===");
			Hash refHash = null;
			boolean converged = true;
			for (int i = 0; i < NUM_NODES; i++) {
				Hash h = Hash.get(servers.get(i).getLocalValue());
				System.out.println("Node " + i + " hash: " + h.toHexString(8) + "...");
				if (refHash == null) refHash = h;
				else if (!refHash.equals(h)) converged = false;
			}
			System.out.println(converged
				? "All nodes converged."
				: "WARNING: nodes diverged.");

			// --- Read back merged replicas into each KVDatabase ---
			System.out.println("\n=== KV Merge ===");
			for (int i = 0; i < NUM_NODES; i++) {
				ACell kvValue = servers.get(i).getCursor().get(Keywords.KV);
				if (kvValue instanceof AHashMap<?,?> kvMap) {
					ACell dbValue = kvMap.get(dbName);
					if (dbValue instanceof AHashMap<?,?>) {
						@SuppressWarnings({"rawtypes"})
						AHashMap ownerMap = (AHashMap) dbValue;
						@SuppressWarnings("unchecked")
						long merged = databases.get(i).mergeReplicas(ownerMap);
						System.out.println("Node " + i + ": merged " + merged + " remote replica(s)");
					}
				}
			}

			// --- Show converged data from Node 0 ---
			System.out.println("\n=== Node 0 - Converged View ===");
			KVDatabase db = databases.get(0);
			System.out.println("user:alice   = " + db.kv().get("user:alice"));
			System.out.println("user:bob     = " + db.kv().get("user:bob"));
			System.out.println("user:charlie = " + db.kv().get("user:charlie"));
			System.out.println("user:dave    = " + db.kv().get("user:dave"));
			System.out.println("tags         = " + db.kv().smembers("tags")
				+ " (card=" + db.kv().scard("tags") + ")");
			System.out.println("visits       = " + db.kv().incrby("visits", 0)
				+ " (PN-counter: 1+1+5 = 7)");

			// --- Propagator stats ---
			System.out.println("\n=== Propagator Stats ===");
			for (int i = 0; i < NUM_NODES; i++) {
				LatticePropagator<?> p = servers.get(i).getPropagator();
				System.out.println("Node " + i + ": "
					+ p.getBroadcastCount() + " broadcasts, "
					+ p.getRootSyncCount() + " root syncs");
			}

		} finally {
			System.out.println("\nShutting down...");
			for (NodeServer<?> s : servers) s.close();
			for (AStore s : stores) s.close();
		}

		System.out.println("=== Demo Complete ===");
		Shutdown.shutdownNow();
	}
}
