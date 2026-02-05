package convex.db;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.db.lattice.LatticeTables;
import convex.db.lattice.SQLDatabase;

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
		LatticeTables tables = db.tables();

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
		LatticeTables tables = db.tables();

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
		LatticeTables tables = db.tables();

		tables.createTable("users", new String[]{"id", "name", "email"});

		// Insert row
		ACell key = CVMLong.create(1);
		AVector<ACell> values = Vectors.of(
			CVMLong.create(1),
			Strings.create("Alice"),
			Strings.create("alice@example.com")
		);
		boolean inserted = tables.insert("users", key, values);
		assertTrue(inserted);
		assertEquals(1, tables.getRowCount("users"));

		// Select row
		AVector<ACell> row = tables.selectByKey("users", key);
		assertNotNull(row);
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
		LatticeTables tables = db.tables();

		tables.createTable("items", new String[]{"id", "name"});

		ACell key = CVMLong.create(1);
		tables.insert("items", key, Vectors.of(CVMLong.create(1), Strings.create("Item")));
		assertEquals(1, tables.getRowCount("items"));

		boolean deleted = tables.deleteByKey("items", key);
		assertTrue(deleted);
		assertEquals(0, tables.getRowCount("items"));
		assertNull(tables.selectByKey("items", key));

		// Can't delete non-existent
		boolean notFound = tables.deleteByKey("items", key);
		assertFalse(notFound);
	}

	@Test
	public void testSelectAll() {
		AKeyPair kp = AKeyPair.generate();
		SQLDatabase db = SQLDatabase.create("testdb", kp);
		LatticeTables tables = db.tables();

		tables.createTable("products", new String[]{"id", "name"});

		tables.insert("products", CVMLong.create(1), Vectors.of(CVMLong.create(1), Strings.create("Apple")));
		tables.insert("products", CVMLong.create(2), Vectors.of(CVMLong.create(2), Strings.create("Banana")));
		tables.insert("products", CVMLong.create(3), Vectors.of(CVMLong.create(3), Strings.create("Cherry")));

		var all = tables.selectAll("products");
		assertEquals(3, all.count());
	}

	@Test
	public void testTableNames() {
		AKeyPair kp = AKeyPair.generate();
		SQLDatabase db = SQLDatabase.create("testdb", kp);
		LatticeTables tables = db.tables();

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
		LatticeTables tables = db.tables();

		tables.createTable("data", new String[]{"id", "value"});
		tables.insert("data", CVMLong.create(1), Vectors.of(CVMLong.create(1), Strings.create("original")));

		// Fork
		LatticeTables forked = tables.fork();
		forked.insert("data", CVMLong.create(2), Vectors.of(CVMLong.create(2), Strings.create("forked")));

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

		db1.tables().insert("counter", CVMLong.create(1), Vectors.of(CVMLong.create(1), CVMLong.create(100)));
		Thread.sleep(10); // Ensure different timestamps
		db2.tables().insert("counter", CVMLong.create(2), Vectors.of(CVMLong.create(2), CVMLong.create(200)));

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
		db2.tables().insert("secure", CVMLong.create(1), Vectors.of(CVMLong.create(1)));

		// Get db2's export and tamper with it by re-signing with wrong key
		var export = db2.exportReplica();
		var signedData = export.get(kp2.getAccountKey());
		var tamperedSigned = kpEvil.signData(signedData.getValue());

		// Create tampered export - must match expected generic type
		@SuppressWarnings({"unchecked", "rawtypes"})
		AHashMap<ACell, SignedData<AHashMap<AString, Index<AString, AVector<ACell>>>>> tamperedExport =
			(AHashMap) convex.core.data.Maps.of(kp2.getAccountKey(), tamperedSigned);

		// Should reject - signature doesn't match owner key
		long merged = db1.mergeReplicas(tamperedExport);
		assertEquals(0, merged);
		assertEquals(0, db1.tables().getRowCount("secure"));
	}
}
