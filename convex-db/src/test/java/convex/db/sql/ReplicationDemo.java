package convex.db.sql;

import java.net.InetSocketAddress;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;
import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.data.prim.CVMLong;
import convex.core.store.AStore;
import convex.db.ConvexDB;
import convex.etch.EtchStore;
import convex.db.lattice.SQLDatabase;
import convex.node.NodeConfig;
import convex.node.NodeServer;

/**
 * Demo: Replicated Lattice SQL Database over a real network.
 *
 * <p>Two independent lattice nodes, each running a NodeServer with the SQL
 * database lattice, connected over the Convex binary protocol. Each node
 * inserts data independently, then a network sync propagates changes —
 * both nodes converge to identical state via lattice merge.
 *
 * <p>Key concepts demonstrated:
 * <ul>
 *   <li>Real NodeServer instances with network connections</li>
 *   <li>Independent SQL inserts on separate nodes</li>
 *   <li>Automatic delta-based propagation via binary protocol</li>
 *   <li>Last-write-wins (LWW) conflict resolution</li>
 *   <li>Tombstone (delete) replication</li>
 *   <li>Verified convergence — identical hashes on both nodes</li>
 * </ul>
 */
public class ReplicationDemo {

	private static final int PORT_LONDON = 19800;
	private static final int PORT_TOKYO  = 19801;

	public static void main(String[] args) throws Exception {
		System.out.println("==================================================");
		System.out.println("     Convex Replicated Lattice SQL Database");
		System.out.println("==================================================");
		System.out.println();

		// Each node gets its own Etch persistent store (temp files, auto-cleaned)
		EtchStore storeLondon = EtchStore.createTemp("london");
		EtchStore storeTokyo  = EtchStore.createTemp("tokyo");

		// Create NodeServers using the SQL database lattice
		NodeServer<?> serverLondon = new NodeServer<>(
				ConvexDB.DATABASE_MAP_LATTICE, storeLondon, NodeConfig.port(PORT_LONDON));
		NodeServer<?> serverTokyo  = new NodeServer<>(
				ConvexDB.DATABASE_MAP_LATTICE, storeTokyo,  NodeConfig.port(PORT_TOKYO));

		try {
			// ── 1. Launch two nodes ─────────────────────────────────────
			serverLondon.launch();
			serverTokyo.launch();

			section("1. Two lattice nodes launched");
			System.out.println("  London node: port " + PORT_LONDON);
			System.out.println("  Tokyo  node: port " + PORT_TOKYO);

			// ── 2. Establish peer connections (bidirectional) ────────────
			// Each node connects to the other as a peer. The LatticePropagator
			// will automatically broadcast deltas over these connections.

			InetSocketAddress addrLondon = new InetSocketAddress("localhost", PORT_LONDON);
			InetSocketAddress addrTokyo  = new InetSocketAddress("localhost", PORT_TOKYO);

			AccountKey keyL = AKeyPair.generate().getAccountKey();
			AccountKey keyT = AKeyPair.generate().getAccountKey();

			Convex londonToTokyo = ConvexRemote.connect(addrTokyo);
			serverLondon.getPropagator().addPeer(keyT, londonToTokyo);

			Convex tokyoToLondon = ConvexRemote.connect(addrLondon);
			serverTokyo.getPropagator().addPeer(keyL, tokyoToLondon);

			section("2. Peer connections established (bidirectional)");

			// ── 3. Connect ConvexDB to each node's cursor ───────────────
			// Each ConvexDB wraps the NodeServer's live cursor, so SQL
			// operations flow through the lattice and into the propagator.

			ConvexDB cdbLondon = ConvexDB.connect(serverLondon.getCursor());
			ConvexDB cdbTokyo  = ConvexDB.connect(serverTokyo.getCursor());

			SQLDatabase dbLondon = cdbLondon.database("inventory");
			SQLDatabase dbTokyo  = cdbTokyo.database("inventory");

			section("3. SQL databases connected to node cursors");

			// ── 4. Create table and insert data independently ───────────

			dbLondon.tables().createTable("products", new String[]{"id", "name", "price", "stock"});
			dbLondon.tables().insert("products", 1, "Widget",    9.99, 100);
			dbLondon.tables().insert("products", 2, "Gadget",   24.99,  50);
			dbLondon.tables().insert("products", 3, "Sprocket", 14.99,  75);
			// Sync so the propagator sees the changes
			serverLondon.getCursor().sync();

			dbTokyo.tables().createTable("products", new String[]{"id", "name", "price", "stock"});
			dbTokyo.tables().insert("products", 4, "Connector",  4.99, 200);
			dbTokyo.tables().insert("products", 5, "Capacitor",  1.99, 500);
			dbTokyo.tables().insert("products", 6, "Transistor", 0.49, 1000);
			serverTokyo.getCursor().sync();

			section("4. Independent inserts (no sync yet)");
			System.out.println("  London: " + dbLondon.tables().getRowCount("products") + " rows (ids 1-3)");
			System.out.println("  Tokyo:  " + dbTokyo.tables().getRowCount("products")  + " rows (ids 4-6)");

			// ── 5. Network sync — pull from peers ───────────────────────
			// Each node queries its peers for the latest lattice value,
			// acquires the full delta, and merges it locally.
			// Two rounds ensure both sides have each other's data
			// (first round may not reflect the other's merge result).

			serverLondon.pull();
			serverTokyo.pull();
			serverLondon.pull();
			serverTokyo.pull();

			section("5. Network sync complete");
			System.out.println("  London: " + dbLondon.tables().getRowCount("products") + " rows");
			System.out.println("  Tokyo:  " + dbTokyo.tables().getRowCount("products")  + " rows");

			showTable("London", dbLondon);
			showTable("Tokyo",  dbTokyo);

			// ── 6. Conflicting updates — LWW resolution ─────────────────

			section("6. Conflicting update on row id=1");
			dbLondon.tables().insert("products", 1, "Widget Pro",   12.99, 80);
			serverLondon.getCursor().sync();
			System.out.println("  London: id=1 -> 'Widget Pro' @ 12.99");

			Thread.sleep(15); // ensure later timestamp

			dbTokyo.tables().insert("products",  1, "Widget Ultra", 15.99, 60);
			serverTokyo.getCursor().sync();
			System.out.println("  Tokyo:  id=1 -> 'Widget Ultra' @ 15.99 (later timestamp)");

			// Sync (two rounds for full convergence with Etch stores)
			serverLondon.pull();
			serverTokyo.pull();
			serverLondon.pull();
			serverTokyo.pull();

			System.out.println();
			System.out.println("  After sync -- both nodes see Tokyo's later write:");
			showRow(dbLondon, "London", 1);
			showRow(dbTokyo,  "Tokyo",  1);

			// ── 7. Delete replication ────────────────────────────────────

			section("7. Delete replication");
			dbTokyo.tables().deleteByKey("products", CVMLong.create(6));
			serverTokyo.getCursor().sync();
			System.out.println("  Tokyo deletes id=6 (Transistor)");

			serverLondon.pull();
			System.out.println("  London syncs -> " + dbLondon.tables().getRowCount("products") + " rows");

			// ── 8. Final converged state ────────────────────────────────

			// One more full sync to ensure both are identical
			serverTokyo.pull();

			section("8. Final converged state");
			showTable("London", dbLondon);

			// Verify convergence by comparing lattice value hashes
			ACell londonValue = serverLondon.getLocalValue();
			ACell tokyoValue  = serverTokyo.getLocalValue();
			Hash londonHash = Hash.get(londonValue);
			Hash tokyoHash  = Hash.get(tokyoValue);

			System.out.println();
			System.out.println("  London hash: " + londonHash.toHexString().substring(0, 16) + "...");
			System.out.println("  Tokyo  hash: " + tokyoHash.toHexString().substring(0, 16) + "...");
			long londonRows = dbLondon.tables().getRowCount("products");
			long tokyoRows  = dbTokyo.tables().getRowCount("products");

			if (londonHash.equals(tokyoHash) && londonRows == tokyoRows) {
				System.out.println("  CONVERGED: " + londonRows + " rows, identical lattice state");
			} else {
				System.out.println("  DIVERGED (unexpected)");
				System.out.println("    London: " + londonRows + " rows");
				System.out.println("    Tokyo:  " + tokyoRows  + " rows");
			}

			System.out.println();
			System.out.println("==================================================");
			System.out.println("  Two nodes, real network, zero coordination.");
			System.out.println("  Lattice merge guarantees convergence.");
			System.out.println("==================================================");

		} finally {
			// Clean shutdown
			serverLondon.close();
			serverTokyo.close();
			storeLondon.close();
			storeTokyo.close();
			// Force exit — Netty threads may linger
			System.exit(0);
		}
	}

	// ── Display helpers ──────────────────────────────────────────────────

	private static void section(String title) {
		System.out.println();
		System.out.println("-- " + title + " --");
	}

	private static void showRow(SQLDatabase db, String label, long id) {
		var row = db.tables().selectByKey("products", CVMLong.create(id));
		if (row != null) {
			System.out.printf("  %-8s id=%d -> %s @ %s (stock: %s)%n",
					label, id, row.get(1), row.get(2), row.get(3));
		}
	}

	private static void showTable(String label, SQLDatabase db) {
		System.out.println();
		System.out.println("  " + label + " [products]:");
		System.out.printf("  %-6s %-16s %-10s %-8s%n", "id", "name", "price", "stock");
		System.out.println("  " + "-".repeat(42));

		var all = db.tables().selectAll("products");
		if (all != null) {
			for (long i = 0; i < all.count(); i++) {
				var entry = all.entryAt(i);
				var values = entry.getValue();
				if (values == null) continue;
				System.out.printf("  %-6s %-16s %-10s %-8s%n",
						values.get(0), values.get(1), values.get(2), values.get(3));
			}
		}
	}
}
