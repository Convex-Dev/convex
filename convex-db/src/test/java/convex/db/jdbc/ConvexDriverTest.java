package convex.db.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.db.ConvexDB;
import convex.db.calcite.ConvexType;
import convex.db.lattice.RowLWW;
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
	public void testParseMemExplicit() throws Exception {
		ConvexDriver driver = new ConvexDriver();
		var parsed = driver.parseURL("jdbc:convex:mem:mydb", new java.util.Properties());
		assertEquals(ConvexDriver.ParsedURL.Mode.MEM, parsed.mode);
		assertEquals("mydb", parsed.identifier);
		assertEquals("mydb", parsed.database);
	}

	@Test
	public void testParseMemShorthand() throws Exception {
		ConvexDriver driver = new ConvexDriver();
		var parsed = driver.parseURL("jdbc:convex:mydb", new java.util.Properties());
		assertEquals(ConvexDriver.ParsedURL.Mode.MEM, parsed.mode);
		assertEquals("mydb", parsed.identifier);
		assertEquals("mydb", parsed.database);
	}

	@Test
	public void testParseFile() throws Exception {
		ConvexDriver driver = new ConvexDriver();
		var parsed = driver.parseURL("jdbc:convex:file:/data/mydb.etch", new java.util.Properties());
		assertEquals(ConvexDriver.ParsedURL.Mode.FILE, parsed.mode);
		assertEquals("/data/mydb.etch", parsed.identifier);
		assertEquals("mydb", parsed.database); // stem of filename
	}

	@Test
	public void testParseFileWithDatabaseParam() throws Exception {
		ConvexDriver driver = new ConvexDriver();
		var parsed = driver.parseURL("jdbc:convex:file:/data/store.etch;database=market", new java.util.Properties());
		assertEquals(ConvexDriver.ParsedURL.Mode.FILE, parsed.mode);
		assertEquals("/data/store.etch", parsed.identifier);
		assertEquals("market", parsed.database);
	}

	@Test
	public void testParseLegacy() throws Exception {
		ConvexDriver driver = new ConvexDriver();
		var parsed = driver.parseURL("jdbc:convex:database=mydb", new java.util.Properties());
		assertEquals(ConvexDriver.ParsedURL.Mode.LEGACY, parsed.mode);
		assertEquals("mydb", parsed.identifier);
		assertEquals("mydb", parsed.database);
	}

	// ========== Connection Parameters ==========

	/** Set once a class that a caller named is initialised. */
	static final AtomicBoolean CANARY_LOADED = new AtomicBoolean();

	/** A class callers name in the tests below; initialising it sets {@link #CANARY_LOADED}. */
	public static class Canary {
		static {
			CANARY_LOADED.set(true);
		}
	}

	private static final String CANARY = Canary.class.getName();

	/** A URL carries only the driver's own parameters, so nothing in it can name a class. */
	@Test
	public void testUrlRefusesOtherParameters() {
		for (String param : new String[] {"schemaFactory=" + CANARY, "parserFactory=" + CANARY,
				"model=" + CANARY, "lex=JAVA", "database", "caseSensitive=maybe"}) {
			assertThrows(SQLException.class, () -> DriverManager.getConnection("jdbc:convex:params_test;" + param), param);
		}
		assertFalse(CANARY_LOADED.get());
	}

	/** Only the driver's own connection properties are read: the rest never reach Calcite. */
	@Test
	public void testOtherPropertiesIgnored() throws Exception {
		Properties props = new Properties();
		props.setProperty("schemaFactory", CANARY);
		props.setProperty("parserFactory", "org.apache.calcite.sql.parser.impl.SqlParserImpl#FACTORY");
		props.setProperty("database", "props_db");
		try (Connection conn = DriverManager.getConnection("jdbc:convex:mem:props_test", props);
				Statement stmt = conn.createStatement()) {
			assertEquals("props_db", conn.getSchema());
			// Parsed by the driver's own DDL parser, not the one the properties named
			stmt.executeUpdate("CREATE TABLE t (id INTEGER)");
		}
		assertFalse(CANARY_LOADED.get());
	}

	/**
	 * Identifier case is the database's setting, not the connection's: a URL connection
	 * follows the standard, and a URL cannot choose otherwise.
	 */
	@Test
	public void testIdentifiersFollowTheDatabase() throws Exception {
		try (Connection conn = DriverManager.getConnection("jdbc:convex:case_test");
				Statement stmt = conn.createStatement()) {
			stmt.executeUpdate("CREATE TABLE t (id INTEGER)");
			assertFalse(stmt.executeQuery("SELECT * FROM \"T\"").next());
			assertThrows(SQLException.class, () -> stmt.executeQuery("SELECT * FROM \"t\""));
		}
		assertThrows(SQLException.class, () -> DriverManager.getConnection("jdbc:convex:case_test;caseSensitive=false"));
	}

	/**
	 * SQL cannot name a class either: DDL other than for tables is refused, and type
	 * and function names resolve only within the schema.
	 */
	@Test
	public void testSqlCannotNameClasses() throws Exception {
		try (Connection conn = DriverManager.getConnection("jdbc:convex:ddl_test");
				Statement stmt = conn.createStatement()) {
			stmt.executeUpdate("CREATE TABLE t (id INTEGER)");
			for (String sql : new String[] {"CREATE FUNCTION f AS '" + CANARY + "'",
					"CREATE FOREIGN SCHEMA s LIBRARY '" + CANARY + "'",
					"CREATE TABLE u (id INTEGER, x \"" + CANARY + "\")",
					"SELECT CAST(id AS \"" + CANARY + "\") FROM t",
					"SELECT \"" + CANARY + "\"(id) FROM t"}) {
				assertThrows(SQLException.class, () -> stmt.execute(sql), sql);
			}
		}
		assertFalse(CANARY_LOADED.get());
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

	/** Regression for #645: generated DML must retain its connection instance. */
	@Test
	public void testDmlIsolationAcrossManagedInstances() throws Exception {
		String a="jdbc:convex:mem:isolation_a";
		String b="jdbc:convex:mem:isolation_b";

		executeUpdate(a,"CREATE TABLE t (id INTEGER, name VARCHAR)");
		assertEquals(1,executeUpdate(a,"INSERT INTO t VALUES (1, 'FromA')"));
		executeUpdate(b,"CREATE TABLE t (id INTEGER, name VARCHAR)");
		assertEquals(1,executeUpdate(b,"INSERT INTO t VALUES (1, 'FromB')"));

		assertEquals(List.of("FromA"),queryStrings(a,"SELECT name FROM t"));
		assertEquals(List.of("FromB"),queryStrings(b,"SELECT name FROM t"));

		assertEquals(1,executeUpdate(b,"UPDATE t SET name = 'UpdatedB' WHERE id = 1"));
		assertEquals(List.of("FromA"),queryStrings(a,"SELECT name FROM t"));
		assertEquals(List.of("UpdatedB"),queryStrings(b,"SELECT name FROM t"));

		assertEquals(1,executeUpdate(b,"DELETE FROM t WHERE id = 1"));
		assertEquals(List.of("FromA"),queryStrings(a,"SELECT name FROM t"));
		assertTrue(queryStrings(b,"SELECT name FROM t").isEmpty());
	}

	/** Regression for #646: Calcite represents a one-column row as a scalar. */
	@Test
	public void testSingleColumnDml() throws Exception {
		String url="jdbc:convex:mem:single_column";
		executeUpdate(url,"CREATE TABLE s (k VARCHAR)");

		assertEquals(1,executeUpdate(url,"INSERT INTO s VALUES ('x')"));
		assertEquals(List.of("x"),queryStrings(url,"SELECT k FROM s"));

		assertEquals(1,executeUpdate(url,"UPDATE s SET k = 'y' WHERE k = 'x'"));
		assertEquals(List.of("y"),queryStrings(url,"SELECT k FROM s"));

		assertEquals(1,executeUpdate(url,"DELETE FROM s WHERE k = 'y'"));
		assertTrue(queryStrings(url,"SELECT k FROM s").isEmpty());
	}

	private static int executeUpdate(String url, String sql) throws Exception {
		try (Connection connection=DriverManager.getConnection(url);
				Statement statement=connection.createStatement()) {
			return statement.executeUpdate(sql);
		}
	}

	private static List<String> queryStrings(String url, String sql) throws Exception {
		ArrayList<String> result=new ArrayList<>();
		try (Connection connection=DriverManager.getConnection(url);
				Statement statement=connection.createStatement();
				ResultSet rs=statement.executeQuery(sql)) {
			while (rs.next()) result.add(rs.getString(1));
		}
		return result;
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

		// Closing the connection saves auto-committed statements to the store root
		ConvexDriver.ManagedInstance inst = ConvexDriver.instances.get("file:" + tempFile.getCanonicalPath());
		assertEquals(inst.server.getHost().cursor().get(), inst.store.getRootData());

		// Data survives closing and reopening the store
		ConvexDriver.closeAll();
		try (Connection conn = DriverManager.getConnection(url);
				Statement stmt = conn.createStatement()) {
			ResultSet rs = stmt.executeQuery("SELECT name FROM t WHERE id = 1");
			assertTrue(rs.next());
			assertEquals("Bob", rs.getString(1));
		}
	}

	@Test
	public void testFileCommitSaves() throws Exception {
		File tempFile = File.createTempFile("convex-driver-commit", ".etch");
		tempFile.delete();
		tempFile.deleteOnExit();
		String url = "jdbc:convex:file:" + tempFile.getAbsolutePath().replace('\\', '/');

		try (Connection conn = DriverManager.getConnection(url)) {
			try (Statement stmt = conn.createStatement()) {
				stmt.executeUpdate("CREATE TABLE t (id INTEGER, name VARCHAR)");
			}
			conn.setAutoCommit(false);
			try (Statement stmt = conn.createStatement()) {
				stmt.executeUpdate("INSERT INTO t VALUES (2, 'Eve')");
			}
			conn.commit();

			// Committed state is saved while the connection stays open
			ConvexDriver.ManagedInstance inst = ConvexDriver.instances.get("file:" + tempFile.getCanonicalPath());
			assertEquals(inst.server.getHost().cursor().get(), inst.store.getRootData());
		}
	}

	// ========== Direct API ==========

	@Test
	public void testDirectGetConnection() throws Exception {
		ConvexDB cdb = ConvexDB.create(RowLWW::merge);
		IdentifierPolicy.LOWER.applyTo(cdb.database("direct_test"));
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

	/** A direct connection leaves the driver's in-memory database of the same name in place (#789). */
	@Test
	public void testDirectConnectionLeavesDriverDatabase() throws Exception {
		try (Connection conn = DriverManager.getConnection("jdbc:convex:direct_shared");
				Statement stmt = conn.createStatement()) {
			stmt.executeUpdate("CREATE TABLE driver_t (id INTEGER)");
		}

		ConvexDB cdb = ConvexDB.create(RowLWW::merge);
		cdb.database("direct_shared").tables().createTable("APP_T", new String[]{"ID"});
		try (Connection conn = cdb.getConnection("direct_shared")) {
			assertEquals(List.of("APP_T"), tableNames(conn));
		}
		try (Connection conn = DriverManager.getConnection("jdbc:convex:direct_shared")) {
			assertEquals(List.of("DRIVER_T"), tableNames(conn), "the URL still reaches the driver's database");
		}
	}

	/** A direct connection's database name is never read as a URL (#789). */
	@Test
	public void testDirectConnectionTakesAnyName() throws Exception {
		ConvexDB cdb = ConvexDB.create(RowLWW::merge);
		for (String name : new String[] {"direct;b", "file:direct_any.etch", "database=direct_any", "direct=any"}) {
			cdb.database(name).tables().createTable("T", new String[]{"ID", "V"},
					new ConvexType[]{ConvexType.INTEGER, ConvexType.VARCHAR});
			try (Connection conn = cdb.getConnection(name); Statement stmt = conn.createStatement()) {
				stmt.executeUpdate("INSERT INTO t VALUES (1, 'one')");
			}
			assertEquals(Vectors.of(1L, "one"), cdb.database(name).tables().selectByKey("T", CVMLong.create(1)), name);
		}
	}

	// ========== Legacy Format ==========

	@Test
	public void testLegacyFormatWithRegistry() throws Exception {
		ConvexDB cdb = ConvexDB.create(RowLWW::merge);
		IdentifierPolicy.LOWER.applyTo(cdb.database("legacy_test"));
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

	// ========== Helpers ==========

	private static List<String> tableNames(Connection conn) throws Exception {
		List<String> names = new ArrayList<>();
		try (ResultSet rs = conn.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
			while (rs.next()) names.add(rs.getString("TABLE_NAME"));
		}
		return names;
	}
}
