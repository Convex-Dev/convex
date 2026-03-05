package convex.db;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.db.lattice.SQLTables;
import convex.db.lattice.SQLDatabase;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.Cursors;

/**
 * Tests for SQLDatabase and LatticeTables.
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
		SQLTables tables = db.tables();

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
		SQLTables tables = db.tables();

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
		SQLTables tables = db.tables();

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
		SQLTables tables = db.tables();

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
		SQLTables tables = db.tables();

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
		SQLTables tables = db.tables();

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
		SQLTables tables = db.tables();

		tables.createTable("data", new String[]{"id", "value"});
		tables.insert("data", 1, "original");

		// Fork
		SQLTables forked = tables.fork();
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

		db1.tables().insert("counter", 1, 100);
		Thread.sleep(10); // Ensure different timestamps
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
			(AHashMap) convex.core.data.Maps.of(kp2.getAccountKey(), tamperedSigned);

		// Should reject - signature doesn't match owner key
		long merged = db1.mergeReplicas(tamperedExport);
		assertEquals(0, merged);
		assertEquals(0, db1.tables().getRowCount("secure"));
	}

	@Test
	public void testConnectedDatabase() throws Exception {
		// Set up a cursor chain using DATABASE_MAP_LATTICE
		ALatticeCursor<?> root = Cursors.createLattice(SQLDatabase.DATABASE_MAP_LATTICE);

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
	public void testOwnerLatticeConstant() {
		// Verify OWNER_LATTICE can verify and merge signed data correctly
		AKeyPair kp1 = AKeyPair.generate();
		AKeyPair kp2 = AKeyPair.generate();

		SQLDatabase db1 = SQLDatabase.create("test", kp1);
		SQLDatabase db2 = SQLDatabase.create("test", kp2);

		db1.tables().createTable("t", new String[]{"id"});
		db2.tables().createTable("t", new String[]{"id"});
		db1.tables().insert("t", 1);
		db2.tables().insert("t", 2);

		// Merge both exports through OWNER_LATTICE
		LatticeContext ctx = LatticeContext.create(null, kp1);
		var merged = SQLDatabase.OWNER_LATTICE.merge(ctx,
			db1.exportReplica(), db2.exportReplica());

		// Should contain both owners
		assertEquals(2, merged.count());
		assertTrue(merged.containsKey(kp1.getAccountKey()));
		assertTrue(merged.containsKey(kp2.getAccountKey()));
	}

	@Test
	public void testForkAndSyncConnected() throws Exception {
		// Set up cursor chain
		ALatticeCursor<?> root = Cursors.createLattice(SQLDatabase.DATABASE_MAP_LATTICE);

		// Connect and populate
		SQLDatabase db = SQLDatabase.connect(root, "mydb");
		db.tables().createTable("data", new String[]{"id", "value"});
		db.tables().insert("data", 1, "original");

		// Fork the tables
		SQLTables forked = db.tables().fork();
		forked.insert("data", 2, "forked");

		// Original unchanged
		assertEquals(1, db.tables().getRowCount("data"));
		assertEquals(2, forked.getRowCount("data"));

		// Sync back
		forked.sync();
		assertEquals(2, db.tables().getRowCount("data"));
	}

	@Test
	public void testConnectedModeRejectsStandaloneOps() {
		// Connected databases should not have signing capability
		ALatticeCursor<?> root = Cursors.createLattice(SQLDatabase.DATABASE_MAP_LATTICE);
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
