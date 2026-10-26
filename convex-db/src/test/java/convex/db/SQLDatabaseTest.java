package convex.db;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BiPredicate;

import org.junit.jupiter.api.Test;

import convex.auth.did.DID;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.data.ACell;
import convex.core.data.AHashMap;

import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;
import convex.db.ConvexDB;
import convex.db.lattice.SQLSchema;
import convex.db.lattice.SQLDatabase;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Cursors;

/**
 * Tests for SQLDatabase and SQLSchema.
 */
public class SQLDatabaseTest {

	@Test
	public void testCreateDatabase() {
		AKeyPair kp = AKeyPair.generate();
		SQLDatabase db = SQLDatabase.create("testdb", kp);

		assertNotNull(db);
		assertEquals("testdb", db.getName().toString());
		assertEquals(kp.getAccountKey(), db.getOwnerKey());
		assertNotNull(db.tables());
	}

	@Test
	public void testCreateTable() {
		AKeyPair kp = AKeyPair.generate();
		SQLDatabase db = SQLDatabase.create("testdb", kp);
		SQLSchema tables = db.tables();

		// Create table
		boolean created = tables.createTable("users", new String[]{"id", "name", "email"});
		assertTrue(created);
		assertTrue(tables.tableExists("users"));

		// Can't create duplicate
		boolean duplicate = tables.createTable("users", new String[]{"id"});
		assertFalse(duplicate);

		// Check schema
		String[] columns = tables.getColumnNames("users");
		assertArrayEquals(new String[]{"id", "name", "email"}, columns);
	}

	@Test
	public void testDropTable() {
		AKeyPair kp = AKeyPair.generate();
		SQLDatabase db = SQLDatabase.create("testdb", kp);
		SQLSchema tables = db.tables();

		tables.createTable("temp", new String[]{"data"});
		assertTrue(tables.tableExists("temp"));

		boolean dropped = tables.dropTable("temp");
		assertTrue(dropped);
		assertFalse(tables.tableExists("temp"));

		// Can't drop non-existent
		boolean notFound = tables.dropTable("temp");
		assertFalse(notFound);
	}

	@Test
	public void testInsertAndSelect() {
		AKeyPair kp = AKeyPair.generate();
		SQLDatabase db = SQLDatabase.create("testdb", kp);
		SQLSchema tables = db.tables();

		tables.createTable("users", new String[]{"id", "name", "email"});

		// Insert row (first column is primary key)
		boolean inserted = tables.insert("users", 1, "Alice", "alice@example.com");
		assertTrue(inserted);
		assertEquals(1, tables.getRowCount("users"));

		// Select row
		AVector<ACell> row = tables.selectByKey("users", CVMLong.create(1));
		assertNotNull(row);
		assertEquals(CVMLong.create(1), row.get(0));
		assertEquals(Strings.create("Alice"), row.get(1));
		assertEquals(Strings.create("alice@example.com"), row.get(2));

		// Select non-existent
		AVector<ACell> notFound = tables.selectByKey("users", CVMLong.create(999));
		assertNull(notFound);
	}

	@Test
	public void testDelete() {
		AKeyPair kp = AKeyPair.generate();
		SQLDatabase db = SQLDatabase.create("testdb", kp);
		SQLSchema tables = db.tables();

		tables.createTable("items", new String[]{"id", "name"});
		tables.insert("items", 1, "Item");
		assertEquals(1, tables.getRowCount("items"));

		boolean deleted = tables.deleteByKey("items", CVMLong.create(1));
		assertTrue(deleted);
		assertEquals(0, tables.getRowCount("items"));
		assertNull(tables.selectByKey("items", CVMLong.create(1)));

		// Can't delete non-existent
		boolean notFound = tables.deleteByKey("items", CVMLong.create(1));
		assertFalse(notFound);
	}

	@Test
	public void testSelectAll() {
		AKeyPair kp = AKeyPair.generate();
		SQLDatabase db = SQLDatabase.create("testdb", kp);
		SQLSchema tables = db.tables();

		tables.createTable("products", new String[]{"id", "name"});
		tables.insert("products", 1, "Apple");
		tables.insert("products", 2, "Banana");
		tables.insert("products", 3, "Cherry");

		var all = tables.selectAll("products");
		assertEquals(3, all.count());
	}

	@Test
	public void testTableNames() {
		AKeyPair kp = AKeyPair.generate();
		SQLDatabase db = SQLDatabase.create("testdb", kp);
		SQLSchema tables = db.tables();

		tables.createTable("alpha", new String[]{"a"});
		tables.createTable("beta", new String[]{"b"});
		tables.createTable("gamma", new String[]{"g"});

		String[] names = tables.getTableNames();
		assertEquals(3, names.length);
	}

	@Test
	public void testForkAndSync() {
		AKeyPair kp = AKeyPair.generate();
		SQLDatabase db = SQLDatabase.create("testdb", kp);
		SQLSchema tables = db.tables();

		tables.createTable("data", new String[]{"id", "value"});
		tables.insert("data", 1, "original");

		// Fork
		SQLSchema forked = tables.fork();
		forked.insert("data", 2, "forked");

		// Original unchanged
		assertEquals(1, tables.getRowCount("data"));
		assertEquals(2, forked.getRowCount("data"));

		// Sync back
		forked.sync();
		assertEquals(2, tables.getRowCount("data"));
	}

	@Test
	public void testReplicaMerge() throws Exception {
		AKeyPair kp1 = AKeyPair.generate();
		AKeyPair kp2 = AKeyPair.generate();

		SQLDatabase db1 = SQLDatabase.create("shared", kp1);
		SQLDatabase db2 = SQLDatabase.create("shared", kp2);

		// Both create same table, both insert
		db1.tables().createTable("counter", new String[]{"id", "count"});
		db2.tables().createTable("counter", new String[]{"id", "count"});

		// No delay needed: rows merge per-key (keys 1 and 2 don't conflict) and
		// both schemas are identical, so equal timestamps cannot change the result
		db1.tables().insert("counter", 1, 100);
		db2.tables().insert("counter", 2, 200);

		// Merge db2's replica into db1
		long merged = db1.mergeReplicas(db2.exportReplica());
		assertEquals(1, merged);

		// db1 now has both rows
		assertEquals(2, db1.tables().getRowCount("counter"));
		assertNotNull(db1.tables().selectByKey("counter", CVMLong.create(1)));
		assertNotNull(db1.tables().selectByKey("counter", CVMLong.create(2)));
	}

	@Test
	public void testInvalidSignatureRejected() {
		AKeyPair kp1 = AKeyPair.generate();
		AKeyPair kp2 = AKeyPair.generate();
		AKeyPair kpEvil = AKeyPair.generate();

		SQLDatabase db1 = SQLDatabase.create("shared", kp1);
		SQLDatabase db2 = SQLDatabase.create("shared", kp2);

		db1.tables().createTable("secure", new String[]{"id"});
		db2.tables().createTable("secure", new String[]{"id"});
		db2.tables().insert("secure", 1);

		// Get db2's export and tamper with it by re-signing with wrong key
		var export = db2.exportReplica();
		var signedData = export.get(kp2.getAccountKey());
		var tamperedSigned = kpEvil.signData(signedData.getValue());

		// Create tampered export - must match expected generic type
		@SuppressWarnings({"unchecked", "rawtypes"})
		AHashMap<ACell, SignedData<AHashMap<AString, Index<Keyword, ACell>>>> tamperedExport =
			(AHashMap) Maps.of(kp2.getAccountKey(), tamperedSigned);

		// Should reject - signature doesn't match owner key
		long merged = db1.mergeReplicas(tamperedExport);
		assertEquals(0, merged);
		assertEquals(0, db1.tables().getRowCount("secure"));
	}

	@Test
	public void testConnectedDatabase() throws Exception {
		// Set up a cursor chain at the database-map level
		AForkableCursor<?> root = Cursors.root(Maps.empty());

		// Connect a named database
		SQLDatabase db = SQLDatabase.connect(root, "mydb");
		assertNotNull(db);
		assertEquals("mydb", db.getName().toString());

		// Use the database
		db.tables().createTable("users", new String[]{"id", "name"});
		db.tables().insert("users", 1, "Alice");
		assertEquals(1, db.tables().getRowCount("users"));

		// Verify the root cursor received the update
		AHashMap<?, ?> rootMap = (AHashMap<?, ?>) root.get();
		assertTrue(rootMap.containsKey(Strings.create("mydb")));

		// Connect a second database to the same root
		SQLDatabase db2 = SQLDatabase.connect(root, "mydb");
		assertEquals(1, db2.tables().getRowCount("users"));
		assertNotNull(db2.tables().selectByKey("users", CVMLong.create(1)));
	}

	@Test
	public void testReplicasFromSeveralOwners() {
		AKeyPair kp1 = AKeyPair.generate();
		AKeyPair kp2 = AKeyPair.generate();
		AKeyPair kp3 = AKeyPair.generate();

		SQLDatabase db1 = SQLDatabase.create("test", kp1);
		SQLDatabase db2 = SQLDatabase.create("test", kp2);
		SQLDatabase db3 = SQLDatabase.create("test", kp3);

		db1.tables().createTable("t", new String[]{"id"});
		db2.tables().createTable("t", new String[]{"id"});
		db3.tables().createTable("t", new String[]{"id"});
		db1.tables().insert("t", 1);
		db2.tables().insert("t", 2);
		db3.tables().insert("t", 3);

		// Each owner's replica is verified separately; this node's own entry is skipped
		var remote = db2.exportReplica().assoc(kp3.getAccountKey(), db3.getSignedState())
			.assoc(kp1.getAccountKey(), db1.getSignedState());
		assertEquals(2, db1.mergeReplicas(remote));
		assertEquals(3, db1.tables().getRowCount("t"));
	}

	// ========== Replica ownership (CAD038) ==========

	/** Creates table t holding one row. */
	private static SQLDatabase withRow(SQLDatabase db, long id) {
		db.tables().createTable("t", new String[]{"id"});
		db.tables().insert("t", id);
		return db;
	}

	/** Files the signer's own signed state under another owner, as a forger would. */
	private static AHashMap<ACell, SignedData<AHashMap<AString, Index<Keyword, ACell>>>> claim(SQLDatabase signer, ACell owner) {
		return signer.exportReplica().dissoc(signer.getOwnerKey()).assoc(owner, signer.getSignedState());
	}

	private static boolean hasRow(SQLDatabase db, long id) {
		return db.tables().tableExists("t") && db.tables().selectByKey("t", CVMLong.create(id)) != null;
	}

	@Test
	public void testDirectOwnerMustBeSigner() {
		AKeyPair owner = AKeyPair.createSeeded(7801);
		AKeyPair attacker = AKeyPair.createSeeded(7802);
		AKeyPair localKey = AKeyPair.createSeeded(7803);
		SQLDatabase source = withRow(SQLDatabase.create("db", attacker), 1);
		for (ACell claimed : new ACell[] {owner.getAccountKey(), DID.forKey(owner.getAccountKey())}) {
			// A verifier is never consulted for an owner that denotes a key directly
			SQLDatabase local = SQLDatabase.create("db", localKey, localKey.getAccountKey(), (o, k) -> true);
			var before = local.cursor().get();
			assertEquals(0, local.mergeReplicas(claim(source, claimed)));
			assertSame(before, local.cursor().get());
			assertThrows(IllegalStateException.class, SQLDatabase.create("db", attacker, claimed)::exportReplica);
			assertThrows(IllegalStateException.class, SQLDatabase.create("db", attacker, claimed, (o, k) -> true)::exportReplica);
		}
	}

	@Test
	public void testDefaultRuleAcceptsSelfCertifyingDID() {
		AKeyPair key = AKeyPair.createSeeded(7804);
		SQLDatabase source = withRow(SQLDatabase.create("db", key, DID.forKey(key.getAccountKey())), 1);
		SQLDatabase local = SQLDatabase.create("db", AKeyPair.createSeeded(7805));
		assertEquals(1, local.mergeReplicas(source.exportReplica()));
		assertTrue(hasRow(local, 1));
	}

	@Test
	public void testDefaultRuleRefusesIndirectOwners() {
		AKeyPair attacker = AKeyPair.createSeeded(7806);
		AKeyPair localKey = AKeyPair.createSeeded(7807);
		SQLDatabase source = withRow(SQLDatabase.create("db", attacker), 1);
		for (ACell claimed : new ACell[] {Address.create(1337), Strings.create("did:web:victim.example"),
				Strings.create("did:convex:1337")}) {
			SQLDatabase local = SQLDatabase.create("db", localKey);
			var before = local.cursor().get();
			assertEquals(0, local.mergeReplicas(claim(source, claimed)), "unverified owners must fail closed");
			assertSame(before, local.cursor().get());
			assertThrows(IllegalStateException.class, SQLDatabase.create("db", attacker, claimed)::exportReplica);
		}
	}

	@Test
	public void testOwnerVerifierAuthorisesIndirectOwners() {
		AKeyPair localKey = AKeyPair.createSeeded(7808);
		AKeyPair authorised = AKeyPair.createSeeded(7809);
		AKeyPair attacker = AKeyPair.createSeeded(7810);
		Address owner = Address.create(1337);
		BiPredicate<ACell, AccountKey> verifier = (o, k) -> owner.equals(o) && authorised.getAccountKey().equals(k);
		SQLDatabase local = SQLDatabase.create("db", localKey, localKey.getAccountKey(), verifier);

		SQLDatabase source = withRow(SQLDatabase.create("db", authorised, owner, verifier), 1);
		var export = source.exportReplica();
		assertEquals(1, local.fork().mergeReplicas(export), "forks keep the verifier");
		assertEquals(1, local.mergeReplicas(export));
		assertTrue(hasRow(local, 1));

		SQLDatabase forger = withRow(SQLDatabase.create("db", attacker), 2);
		assertEquals(0, local.mergeReplicas(claim(forger, owner)));
		assertFalse(hasRow(local, 2));
		assertThrows(IllegalStateException.class, SQLDatabase.create("db", attacker, owner, verifier)::exportReplica);
	}

	/** A database whose override resolves one Address owner to one authorised key. */
	private static SQLDatabase resolving(AKeyPair keyPair, ACell ownerKey, Address address, AccountKey authorised) {
		return new SQLDatabase(Cursors.root(Index.none()), Strings.create("db"), keyPair, ownerKey, null,
				Utils::getCurrentTimestampCell) {
			@Override protected boolean acceptsReplica(ACell owner, SignedData<?> replica) {
				if (!address.equals(owner)) return super.acceptsReplica(owner, replica);
				return authorised.equals(replica.getAccountKey()) && replica.checkSignature();
			}
		};
	}

	@Test
	public void testAcceptsReplicaOverrideGovernsMergeAndExport() {
		AKeyPair localKey = AKeyPair.createSeeded(7811);
		AKeyPair authorised = AKeyPair.createSeeded(7812);
		AKeyPair attacker = AKeyPair.createSeeded(7813);
		Address owner = Address.create(42);
		SQLDatabase local = resolving(localKey, localKey.getAccountKey(), owner, authorised.getAccountKey());

		SQLDatabase source = withRow(resolving(authorised, owner, owner, authorised.getAccountKey()), 1);
		assertEquals(1, local.mergeReplicas(source.exportReplica()));
		assertTrue(hasRow(local, 1));

		SQLDatabase forger = withRow(SQLDatabase.create("db", attacker), 2);
		assertEquals(0, local.mergeReplicas(claim(forger, owner)));
		assertFalse(hasRow(local, 2));
		assertThrows(IllegalStateException.class, resolving(attacker, owner, owner, authorised.getAccountKey())::exportReplica);
	}

	@Test
	public void testForkAndSyncConnected() throws Exception {
		// Set up cursor chain
		AForkableCursor<?> root = Cursors.root(Maps.empty());

		// Connect and populate
		SQLDatabase db = SQLDatabase.connect(root, "mydb");
		db.tables().createTable("data", new String[]{"id", "value"});
		db.tables().insert("data", 1, "original");

		// Fork the tables
		SQLSchema forked = db.tables().fork();
		forked.insert("data", 2, "forked");

		// Original unchanged
		assertEquals(1, db.tables().getRowCount("data"));
		assertEquals(2, forked.getRowCount("data"));

		// Sync back
		forked.sync();
		assertEquals(2, db.tables().getRowCount("data"));
	}

	// ========== Transaction Tests (SQLDatabase.fork/sync) ==========

	@Test
	public void testDatabaseForkNotVisibleUntilCommitted() {
		AKeyPair kp = AKeyPair.generate();
		SQLDatabase db = SQLDatabase.create("testdb", kp);
		db.tables().createTable("users", new String[]{"id", "name"});
		db.tables().insert("users", 1, "Alice");

		// Fork for transaction
		SQLDatabase tx = db.fork();
		tx.tables().insert("users", 2, "Bob");

		// Transaction sees its own write
		assertEquals(2, tx.tables().getRowCount("users"));

		// Parent does NOT see uncommitted data
		assertEquals(1, db.tables().getRowCount("users"));
		assertNull(db.tables().selectByKey("users", CVMLong.create(2)));
	}

	@Test
	public void testDatabaseForkVisibleAfterCommit() {
		AKeyPair kp = AKeyPair.generate();
		SQLDatabase db = SQLDatabase.create("testdb", kp);
		db.tables().createTable("users", new String[]{"id", "name"});
		db.tables().insert("users", 1, "Alice");

		// Fork, insert, commit
		SQLDatabase tx = db.fork();
		tx.tables().insert("users", 2, "Bob");
		tx.sync(); // commit

		// Parent now sees committed data
		assertEquals(2, db.tables().getRowCount("users"));
		assertNotNull(db.tables().selectByKey("users", CVMLong.create(2)));
	}

	@Test
	public void testDatabaseForkRollbackDiscardsChanges() {
		AKeyPair kp = AKeyPair.generate();
		SQLDatabase db = SQLDatabase.create("testdb", kp);
		db.tables().createTable("users", new String[]{"id", "name"});
		db.tables().insert("users", 1, "Alice");

		// Fork, insert, don't sync (rollback)
		SQLDatabase tx = db.fork();
		tx.tables().insert("users", 2, "Bob");
		// tx is discarded — no sync()

		// Parent unchanged
		assertEquals(1, db.tables().getRowCount("users"));
		assertNull(db.tables().selectByKey("users", CVMLong.create(2)));
	}

	@Test
	public void testDatabaseForkAcrossSchemas() {
		// Transaction should be ACID across all sections of the database
		AKeyPair kp = AKeyPair.generate();
		SQLDatabase db = SQLDatabase.create("testdb", kp);
		db.tables().createTable("orders", new String[]{"id", "item"});
		db.tables().createTable("items", new String[]{"id", "name"});

		// Fork at database level — encompasses all tables
		SQLDatabase tx = db.fork();
		tx.tables().insert("orders", 1, "widget");
		tx.tables().insert("items", 1, "Widget");

		// Neither table shows uncommitted data in parent
		assertEquals(0, db.tables().getRowCount("orders"));
		assertEquals(0, db.tables().getRowCount("items"));

		// Commit — both tables updated atomically
		tx.sync();
		assertEquals(1, db.tables().getRowCount("orders"));
		assertEquals(1, db.tables().getRowCount("items"));
	}

	@Test
	public void testMultipleDatabaseRegistry() throws Exception {
		// Create and register two databases via ConvexDB
		ConvexDB cdb = ConvexDB.create();
		SQLDatabase db1 = cdb.database("db_alpha");
		SQLDatabase db2 = cdb.database("db_beta");
		db1.tables().createTable("t1", new String[]{"id", "val"});
		db2.tables().createTable("t2", new String[]{"id", "val"});
		db1.tables().insert("t1", 1, "alpha");
		db2.tables().insert("t2", 1, "beta");

		cdb.register("db_alpha");
		cdb.register("db_beta");

		try {
			// Connect to each database independently
			try (java.sql.Connection conn1 = java.sql.DriverManager.getConnection("jdbc:convex:database=db_alpha");
				 java.sql.Connection conn2 = java.sql.DriverManager.getConnection("jdbc:convex:database=db_beta")) {

				// Each connection sees only its own tables
				try (java.sql.Statement stmt1 = conn1.createStatement();
					 java.sql.ResultSet rs1 = stmt1.executeQuery("SELECT val FROM t1 WHERE id = 1")) {
					assertTrue(rs1.next());
					assertEquals("alpha", rs1.getObject(1));
				}
				try (java.sql.Statement stmt2 = conn2.createStatement();
					 java.sql.ResultSet rs2 = stmt2.executeQuery("SELECT val FROM t2 WHERE id = 1")) {
					assertTrue(rs2.next());
					assertEquals("beta", rs2.getObject(1));
				}
			}

			// Verify lookup returns databases with the correct data
			assertNotNull(ConvexDB.lookupDatabase("db_alpha"));
			assertNotNull(ConvexDB.lookupDatabase("db_beta"));
			assertNull(ConvexDB.lookupDatabase("nonexistent"));
			assertEquals(1, ConvexDB.lookupDatabase("db_alpha").tables().getRowCount("t1"));
			assertEquals(1, ConvexDB.lookupDatabase("db_beta").tables().getRowCount("t2"));
		} finally {
			cdb.unregister("db_alpha");
			cdb.unregister("db_beta");
		}
	}

	@Test
	public void testConnectedModeRejectsStandaloneOps() {
		// Connected databases should not have signing capability
		AForkableCursor<?> root = Cursors.root(Maps.empty());
		SQLDatabase db = SQLDatabase.connect(root, "mydb");

		assertNull(db.getKeyPair());
		assertNull(db.getOwnerKey());
		assertThrows(IllegalStateException.class, () -> db.getSignedState());
		assertThrows(IllegalStateException.class, () -> db.exportReplica());

		// mergeReplicas with non-empty map should also throw
		AKeyPair kp = AKeyPair.generate();
		SQLDatabase standalone = SQLDatabase.create("mydb", kp);
		standalone.tables().createTable("t", new String[]{"id"});
		var export = standalone.exportReplica();
		assertThrows(IllegalStateException.class, () -> db.mergeReplicas(export));
	}
}
