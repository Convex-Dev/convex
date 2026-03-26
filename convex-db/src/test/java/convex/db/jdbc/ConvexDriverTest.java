package convex.db.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import convex.db.ConvexDB;
import convex.db.calcite.ConvexType;
import convex.node.NodeServer;

/**
 * Tests for the ConvexDriver URL formats and connection model.
 */
public class ConvexDriverTest {

	@AfterEach
	public void tearDown() {
		ConvexDriver.closeAll();
	}

	// ========== URL Parsing ==========

	@Test
	public void testParseMemExplicit() {
		ConvexDriver driver = new ConvexDriver();
		var parsed = driver.parseURL("jdbc:convex:mem:mydb", new java.util.Properties());
		assertEquals(ConvexDriver.ParsedURL.Mode.MEM, parsed.mode);
		assertEquals("mydb", parsed.identifier);
		assertEquals("mydb", parsed.database);
	}

	@Test
	public void testParseMemShorthand() {
		ConvexDriver driver = new ConvexDriver();
		var parsed = driver.parseURL("jdbc:convex:mydb", new java.util.Properties());
		assertEquals(ConvexDriver.ParsedURL.Mode.MEM, parsed.mode);
		assertEquals("mydb", parsed.identifier);
		assertEquals("mydb", parsed.database);
	}

	@Test
	public void testParseFile() {
		ConvexDriver driver = new ConvexDriver();
		var parsed = driver.parseURL("jdbc:convex:file:/data/mydb.etch", new java.util.Properties());
		assertEquals(ConvexDriver.ParsedURL.Mode.FILE, parsed.mode);
		assertEquals("/data/mydb.etch", parsed.identifier);
		assertEquals("mydb", parsed.database); // stem of filename
	}

	@Test
	public void testParseFileWithDatabaseParam() {
		ConvexDriver driver = new ConvexDriver();
		var parsed = driver.parseURL("jdbc:convex:file:/data/store.etch;database=market", new java.util.Properties());
		assertEquals(ConvexDriver.ParsedURL.Mode.FILE, parsed.mode);
		assertEquals("/data/store.etch", parsed.identifier);
		assertEquals("market", parsed.database);
	}

	@Test
	public void testParseLegacy() {
		ConvexDriver driver = new ConvexDriver();
		var parsed = driver.parseURL("jdbc:convex:database=mydb", new java.util.Properties());
		assertEquals(ConvexDriver.ParsedURL.Mode.LEGACY, parsed.mode);
		assertEquals("mydb", parsed.identifier);
		assertEquals("mydb", parsed.database);
	}

	// ========== In-Memory Connections ==========

	@Test
	public void testMemConnection() throws Exception {
		try (Connection conn = DriverManager.getConnection("jdbc:convex:mem:driver_test")) {
			assertNotNull(conn);
			try (Statement stmt = conn.createStatement()) {
				stmt.executeUpdate("CREATE TABLE t (id INTEGER, name VARCHAR)");
				stmt.executeUpdate("INSERT INTO t VALUES (1, 'Alice')");
				ResultSet rs = stmt.executeQuery("SELECT name FROM t WHERE id = 1");
				assertTrue(rs.next());
				assertEquals("Alice", rs.getString(1));
			}
		}
	}

	@Test
	public void testMemShorthandConnection() throws Exception {
		try (Connection conn = DriverManager.getConnection("jdbc:convex:shorthand_test")) {
			assertNotNull(conn);
			try (Statement stmt = conn.createStatement()) {
				stmt.executeUpdate("CREATE TABLE t (id INTEGER, val VARCHAR)");
				stmt.executeUpdate("INSERT INTO t VALUES (1, 'hello')");
				ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM t");
				assertTrue(rs.next());
				assertEquals(1, rs.getLong(1));
			}
		}
	}

	@Test
	public void testMemSharedAcrossConnections() throws Exception {
		// First connection creates table and inserts
		try (Connection conn1 = DriverManager.getConnection("jdbc:convex:mem:shared_test")) {
			try (Statement stmt = conn1.createStatement()) {
				stmt.executeUpdate("CREATE TABLE t (id INTEGER, name VARCHAR)");
				stmt.executeUpdate("INSERT INTO t VALUES (1, 'Alice')");
			}
		}

		// Second connection to same name sees the data
		try (Connection conn2 = DriverManager.getConnection("jdbc:convex:mem:shared_test")) {
			try (Statement stmt = conn2.createStatement()) {
				ResultSet rs = stmt.executeQuery("SELECT name FROM t WHERE id = 1");
				assertTrue(rs.next());
				assertEquals("Alice", rs.getString(1));
			}
		}
	}

	// ========== File-Backed Connections ==========

	@Test
	public void testFileConnection() throws Exception {
		File tempFile = File.createTempFile("convex-driver-test", ".etch");
		tempFile.delete(); // EtchStore.create will create it
		tempFile.deleteOnExit();

		String url = "jdbc:convex:file:" + tempFile.getAbsolutePath().replace('\\', '/');

		try (Connection conn = DriverManager.getConnection(url)) {
			assertNotNull(conn);
			try (Statement stmt = conn.createStatement()) {
				stmt.executeUpdate("CREATE TABLE t (id INTEGER, name VARCHAR)");
				stmt.executeUpdate("INSERT INTO t VALUES (1, 'Bob')");
				ResultSet rs = stmt.executeQuery("SELECT name FROM t WHERE id = 1");
				assertTrue(rs.next());
				assertEquals("Bob", rs.getString(1));
			}
		}

		assertTrue(tempFile.exists(), "Etch file should exist after connection");
	}

	// ========== Direct API ==========

	@Test
	public void testDirectGetConnection() throws Exception {
		ConvexDB cdb = ConvexDB.create();
		cdb.database("direct_test").tables().createTable("t",
				new String[]{"id", "name"},
				new ConvexType[]{ConvexType.INTEGER, ConvexType.VARCHAR});

		try (Connection conn = cdb.getConnection("direct_test")) {
			assertNotNull(conn);
			try (Statement stmt = conn.createStatement()) {
				stmt.executeUpdate("INSERT INTO t VALUES (1, 'Charlie')");
				ResultSet rs = stmt.executeQuery("SELECT name FROM t WHERE id = 1");
				assertTrue(rs.next());
				assertEquals("Charlie", rs.getString(1));
			}
		}
	}

	// ========== Legacy Format ==========

	@Test
	public void testLegacyFormatWithRegistry() throws Exception {
		ConvexDB cdb = ConvexDB.create();
		cdb.database("legacy_test").tables().createTable("t",
				new String[]{"id", "name"},
				new ConvexType[]{ConvexType.INTEGER, ConvexType.VARCHAR});
		cdb.register("legacy_test");

		try (Connection conn = DriverManager.getConnection("jdbc:convex:database=legacy_test")) {
			assertNotNull(conn);
			try (Statement stmt = conn.createStatement()) {
				stmt.executeUpdate("INSERT INTO t VALUES (1, 'Legacy')");
				ResultSet rs = stmt.executeQuery("SELECT name FROM t WHERE id = 1");
				assertTrue(rs.next());
				assertEquals("Legacy", rs.getString(1));
			}
		} finally {
			cdb.unregister("legacy_test");
		}
	}
}
