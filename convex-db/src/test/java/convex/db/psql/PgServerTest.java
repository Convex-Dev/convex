package convex.db.psql;

import static org.junit.jupiter.api.Assertions.*;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.db.ConvexDB;
import convex.db.calcite.ConvexColumnType;

import convex.db.calcite.ConvexType;
import convex.db.lattice.RowLWW;
import convex.db.lattice.SQLDatabase;

/**
 * Tests for the PostgreSQL wire protocol server.
 */
public class PgServerTest {

	private static final AtomicInteger DB_COUNTER = new AtomicInteger(0);
	private PgServer server;
	private ConvexDB cdb;
	private SQLDatabase db;
	private String dbName;

	@BeforeEach
	public void setUp() throws Exception {
		// Get unique database name
		dbName = "pgtest_" + DB_COUNTER.getAndIncrement();

		// Create and register a test database
		cdb = ConvexDB.create(RowLWW::merge);
		db = cdb.database(dbName);
		cdb.register(dbName);

		// Create users table using SQLSchema API
		ConvexColumnType[] userTypes = {
			ConvexColumnType.of(ConvexType.INTEGER),   // id
			ConvexColumnType.varchar(50),              // name
			ConvexColumnType.varchar(100)              // email
		};
		db.tables().createTable("users", new String[]{"id", "name", "email"}, userTypes);
		db.tables().insert("users", 1L, "Alice", "alice@example.com");
		db.tables().insert("users", 2L, "Bob", "bob@example.com");

		// Start server on dynamic port (port 0 lets OS assign)
		server = PgServer.builder()
			.port(0)
			.database(dbName)
			.build();
		server.start();
	}

	@AfterEach
	public void tearDown() {
		if (server != null && server.isRunning()) {
			server.stop();
		}
		if (cdb != null) {
			cdb.unregister(dbName);
		}
	}

	@Test
	public void testServerStartAndStop() {
		assertTrue(server.isRunning());
		assertEquals(server.getPort(), server.getPort());

		server.stop();
		assertFalse(server.isRunning());
	}

	@Test
	public void testSSLRequest() throws IOException {
		try (Socket socket = new Socket("localhost", server.getPort())) {
			DataOutputStream out = new DataOutputStream(socket.getOutputStream());
			DataInputStream in = new DataInputStream(socket.getInputStream());

			// Send SSL request
			out.writeInt(8); // length
			out.writeInt(80877103); // SSL request code
			out.flush();

			// Should respond with 'N' (no SSL)
			int response = in.read();
			assertEquals('N', response);
		}
	}

	@Test
	public void testStartupAndAuthentication() throws IOException {
		try (Socket socket = new Socket("localhost", server.getPort())) {
			DataOutputStream out = new DataOutputStream(socket.getOutputStream());
			DataInputStream in = new DataInputStream(socket.getInputStream());

			// Send startup message
			sendStartupMessage(out, dbName, "testuser");

			// Read AuthenticationOk
			byte type = in.readByte();
			assertEquals('R', type); // AuthenticationRequest

			int length = in.readInt();
			int authType = in.readInt();
			assertEquals(0, authType); // AuthenticationOk

			// Read parameter status messages until ReadyForQuery
			while (true) {
				type = in.readByte();
				length = in.readInt();
				if (type == 'Z') { // ReadyForQuery
					byte status = in.readByte();
					assertEquals('I', status); // Idle
					break;
				} else {
					// Skip the message content
					byte[] content = new byte[length - 4];
					in.readFully(content);
				}
			}
		}
	}

	@Test
	public void testSimpleQuery() throws IOException {
		try (Socket socket = new Socket("localhost", server.getPort())) {
			DataOutputStream out = new DataOutputStream(socket.getOutputStream());
			DataInputStream in = new DataInputStream(socket.getInputStream());

			// Authenticate
			sendStartupMessage(out, dbName, "testuser");
			skipToReadyForQuery(in);

			// Query existing data
			sendQuery(out, "SELECT * FROM users WHERE id = 1");

			// Should receive RowDescription, DataRow, CommandComplete, ReadyForQuery
			byte type = in.readByte();
			assertEquals('T', type); // RowDescription

			int length = in.readInt();
			byte[] rowDescData = new byte[length - 4];
			in.readFully(rowDescData);

			// DataRow
			type = in.readByte();
			assertEquals('D', type);
			length = in.readInt();
			byte[] dataRowData = new byte[length - 4];
			in.readFully(dataRowData);

			// CommandComplete
			type = in.readByte();
			assertEquals('C', type);
			length = in.readInt();
			byte[] completeData = new byte[length - 4];
			in.readFully(completeData);
			String completeMsg = new String(completeData, StandardCharsets.UTF_8);
			assertTrue(completeMsg.startsWith("SELECT"));

			// ReadyForQuery
			type = in.readByte();
			assertEquals('Z', type);
		}
	}

	@Test
	public void testCastSyntax() throws IOException {
		try (Socket socket = new Socket("localhost", server.getPort())) {
			DataOutputStream out = new DataOutputStream(socket.getOutputStream());
			DataInputStream in = new DataInputStream(socket.getInputStream());

			sendStartupMessage(out, dbName, "testuser");
			skipToReadyForQuery(in);

			// ::int4 must strip as a whole cast, not leave a stray '4' on the column name
			sendQuery(out, "SELECT id::int4 FROM users WHERE id = 1");
			assertEquals("TDCZ", readMessageTypes(in));
		}
	}

	@Test
	public void testTildeInLiteral() throws IOException {
		try (Socket socket = new Socket("localhost", server.getPort())) {
			DataOutputStream out = new DataOutputStream(socket.getOutputStream());
			DataInputStream in = new DataInputStream(socket.getInputStream());

			sendStartupMessage(out, dbName, "testuser");
			skipToReadyForQuery(in);

			// A '~' inside a string literal is data, not a regex operator
			sendQuery(out, "SELECT name FROM users WHERE email <> '~/nobody'");
			assertEquals("TDDCZ", readMessageTypes(in));
		}
	}

	@Test
	public void testPasswordAuthentication() throws Exception {
		// Stop the trust-auth server
		server.stop();

		// Start a password-protected server on dynamic port
		PgServer pwdServer = PgServer.builder()
			.port(0)
			.database(dbName)
			.password("secret123")
			.build();
		pwdServer.start();

		try (Socket socket = new Socket("localhost", pwdServer.getPort())) {
			DataOutputStream out = new DataOutputStream(socket.getOutputStream());
			DataInputStream in = new DataInputStream(socket.getInputStream());

			// Send startup
			sendStartupMessage(out, dbName, "testuser");

			// Should receive AuthenticationCleartextPassword
			byte type = in.readByte();
			assertEquals('R', type);
			int length = in.readInt();
			int authType = in.readInt();
			assertEquals(3, authType); // CleartextPassword

			// Send password
			sendPassword(out, "secret123");

			// Should receive AuthenticationOk
			type = in.readByte();
			assertEquals('R', type);
			length = in.readInt();
			authType = in.readInt();
			assertEquals(0, authType); // Ok

			skipToReadyForQuery(in);
		} finally {
			pwdServer.stop();
		}
	}

	@Test
	public void testWrongPassword() throws Exception {
		// Stop the trust-auth server
		server.stop();

		// Start a password-protected server on dynamic port
		PgServer pwdServer = PgServer.builder()
			.port(0)
			.database(dbName)
			.password("secret123")
			.build();
		pwdServer.start();

		try (Socket socket = new Socket("localhost", pwdServer.getPort())) {
			socket.setSoTimeout(5000); // 5 second timeout
			DataOutputStream out = new DataOutputStream(socket.getOutputStream());
			DataInputStream in = new DataInputStream(socket.getInputStream());

			// Send startup
			sendStartupMessage(out, dbName, "testuser");

			// Should receive AuthenticationCleartextPassword
			byte type = in.readByte();
			assertEquals('R', type);
			in.readInt();
			in.readInt();

			// Send wrong password
			sendPassword(out, "wrongpassword");

			// Should receive ErrorResponse
			type = in.readByte();
			assertEquals('E', type); // ErrorResponse
		} finally {
			pwdServer.stop();
		}
	}

	@Test
	public void testEmptyQuery() throws IOException {
		try (Socket socket = new Socket("localhost", server.getPort())) {
			DataOutputStream out = new DataOutputStream(socket.getOutputStream());
			DataInputStream in = new DataInputStream(socket.getInputStream());

			sendStartupMessage(out, dbName, "testuser");
			skipToReadyForQuery(in);

			// Send empty query
			sendQuery(out, "");

			// Should receive EmptyQueryResponse (type 'I', length 4)
			byte type = in.readByte();
			assertEquals('I', type); // EmptyQueryResponse
			int length = in.readInt();
			assertEquals(4, length);

			// Should receive ReadyForQuery
			type = in.readByte();
			assertEquals('Z', type);
		}
	}

	@Test
	public void testTerminate() throws IOException {
		try (Socket socket = new Socket("localhost", server.getPort())) {
			DataOutputStream out = new DataOutputStream(socket.getOutputStream());
			DataInputStream in = new DataInputStream(socket.getInputStream());

			sendStartupMessage(out, dbName, "testuser");
			skipToReadyForQuery(in);

			// Send terminate
			out.writeByte('X');
			out.writeInt(4); // length
			out.flush();

			// Connection should close
			socket.setSoTimeout(1000);
			int result = in.read();
			assertEquals(-1, result); // EOF
		}
	}

	/**
	 * The PostgreSQL JDBC driver sends numeric parameters in binary format (setLong as
	 * the 8 bytes of an int8). They must reach the statement as numbers.
	 */
	@Test
	public void testPreparedStatementWithNumericParameters() throws Exception {
		String url = "jdbc:postgresql://localhost:" + server.getPort() + "/" + dbName + "?user=testuser";
		try (Connection conn = DriverManager.getConnection(url)) {
			try (java.sql.PreparedStatement insert = conn.prepareStatement("INSERT INTO users VALUES (?, ?, ?)")) {
				insert.setLong(1, 3L);
				insert.setString(2, "Carol");
				insert.setString(3, "carol@example.com");
				assertEquals(1, insert.executeUpdate());

				insert.setInt(1, 4);
				insert.setString(2, "Dave");
				insert.setString(3, "dave@example.com");
				assertEquals(1, insert.executeUpdate());

				insert.setShort(1, (short) 5);
				insert.setString(2, "Erin");
				insert.setString(3, "erin@example.com");
				assertEquals(1, insert.executeUpdate());

				// Past the driver's prepare threshold the statement becomes a named
				// server-side one; the parameters must still arrive as numbers
				for (int id = 6; id <= 15; id++) {
					insert.setLong(1, id);
					insert.setString(2, "User" + id);
					insert.setString(3, "user" + id + "@example.com");
					assertEquals(1, insert.executeUpdate());
				}
			}
			try (java.sql.PreparedStatement update = conn.prepareStatement("UPDATE users SET name = ? WHERE id = ?")) {
				update.setString(1, "Caroline");
				update.setLong(2, 3L);
				assertEquals(1, update.executeUpdate());
			}

		}

		// Read back through the table API: the rows are stored under numeric keys
		String[] expected = {"Caroline", "Dave", "Erin"};
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i], nameOf(3 + i));
		}
		for (int id = 6; id <= 15; id++) {
			assertEquals("User" + id, nameOf(id));
		}
		assertEquals(15, db.tables().selectAll("users").count());
	}

	private String nameOf(long id) {
		convex.core.data.AVector<convex.core.data.ACell> row = db.tables().selectByKey("users", convex.core.data.prim.CVMLong.create(id));
		assertNotNull(row, "row " + id);
		return row.get(1).toString();
	}

	/**
	 * Floating point parameters arrive in binary format too (setDouble as a float8,
	 * setFloat as a float4).
	 */
	@Test
	public void testPreparedStatementWithFloatingPointParameters() throws Exception {
		ConvexColumnType[] types = {
			ConvexColumnType.of(ConvexType.INTEGER),
			ConvexColumnType.of(ConvexType.DOUBLE)
		};
		db.tables().createTable("readings", new String[]{"id", "amount"}, types);

		String url = "jdbc:postgresql://localhost:" + server.getPort() + "/" + dbName + "?user=testuser";
		try (Connection conn = DriverManager.getConnection(url)) {
			try (java.sql.PreparedStatement insert = conn.prepareStatement("INSERT INTO readings VALUES (?, ?)")) {
				insert.setLong(1, 1L);
				insert.setDouble(2, 2.5);
				assertEquals(1, insert.executeUpdate());

				insert.setLong(1, 2L);
				insert.setFloat(2, 0.25f);
				assertEquals(1, insert.executeUpdate());
			}
		}

		assertEquals(convex.core.data.prim.CVMDouble.create(2.5), db.tables().selectByKey("readings", convex.core.data.prim.CVMLong.create(1)).get(1));
		assertEquals(convex.core.data.prim.CVMDouble.create(0.25), db.tables().selectByKey("readings", convex.core.data.prim.CVMLong.create(2)).get(1));
	}

	/**
	 * Tests connectivity using the PostgreSQL JDBC driver.
	 */
	@Test
	public void testPostgresJDBCDriver() throws Exception {
		// Connect using PostgreSQL JDBC driver
		String url = "jdbc:postgresql://localhost:" + server.getPort() + "/" + dbName + "?user=testuser";
		try (Connection conn = DriverManager.getConnection(url)) {
			assertNotNull(conn);
			assertFalse(conn.isClosed());

			// Execute query
			try (Statement stmt = conn.createStatement();
				 ResultSet rs = stmt.executeQuery("SELECT name, email FROM users ORDER BY id")) {

				assertTrue(rs.next());
				assertEquals("Alice", rs.getString("name"));
				assertEquals("alice@example.com", rs.getString("email"));

				assertTrue(rs.next());
				assertEquals("Bob", rs.getString("name"));
				assertEquals("bob@example.com", rs.getString("email"));

				assertFalse(rs.next());
			}
		}
	}

	/**
	 * The driver asks for the row description once, with Describe, and then executes
	 * the same statement again without asking. Execute itself must not send one.
	 */
	@Test
	public void testPostgresJDBCPreparedSelectRepeated() throws Exception {
		String url = "jdbc:postgresql://localhost:" + server.getPort() + "/" + dbName + "?user=testuser";
		try (Connection conn = DriverManager.getConnection(url);
			 java.sql.PreparedStatement select = conn.prepareStatement("SELECT name FROM users WHERE email = ?")) {
			// More often than the driver's prepare threshold, so that the statement
			// becomes a named one whose description the driver has cached
			for (int i = 0; i < 12; i++) {
				boolean alice = (i % 2 == 0);
				select.setString(1, alice ? "alice@example.com" : "bob@example.com");
				try (ResultSet rs = select.executeQuery()) {
					assertTrue(rs.next());
					assertEquals(alice ? "Alice" : "Bob", rs.getString("name"));
					assertFalse(rs.next());
				}
			}
		}
	}

	/**
	 * Binary numeric parameters and extended-query result rows work together, also
	 * after the driver starts reusing a named prepared statement.
	 */
	@Test
	public void testPostgresJDBCPreparedNumericSelectRepeated() throws Exception {
		String url = "jdbc:postgresql://localhost:" + server.getPort() + "/" + dbName + "?user=testuser";
		try (Connection conn = DriverManager.getConnection(url);
			 java.sql.PreparedStatement select = conn.prepareStatement("SELECT name FROM users WHERE id = ?")) {
			for (int i = 0; i < 12; i++) {
				boolean alice = (i % 2 == 0);
				select.setLong(1, alice ? 1L : 2L);
				try (ResultSet rs = select.executeQuery()) {
					assertTrue(rs.next());
					assertEquals(alice ? "Alice" : "Bob", rs.getString("name"));
					assertFalse(rs.next());
				}
			}
		}
	}

	/**
	 * With stringtype=unspecified the driver sends setString as text with no declared type,
	 * leaving the server to type it from the query, as clients such as node-postgres do for
	 * every parameter. A number in that text compares as a number; text that is not one is
	 * an error.
	 */
	@Test
	public void testPostgresJDBCUntypedTextParameter() throws Exception {
		String url = "jdbc:postgresql://localhost:" + server.getPort() + "/" + dbName + "?user=testuser&stringtype=unspecified";
		try (Connection conn = DriverManager.getConnection(url);
			 java.sql.PreparedStatement select = conn.prepareStatement("SELECT name FROM users WHERE id = ?")) {
			select.setString(1, "2");
			try (ResultSet rs = select.executeQuery()) {
				assertTrue(rs.next());
				assertEquals("Bob", rs.getString("name"));
				assertFalse(rs.next());
			}

			select.setString(1, "not-a-number");
			java.sql.SQLException e = assertThrows(java.sql.SQLException.class, select::executeQuery);
			assertEquals("22P02", e.getSQLState());
		}
	}

	/**
	 * pgjdbc sends a batch as one pipeline ending in a single Sync. After a statement
	 * fails, the server must discard the rest until Sync, so no later statement runs.
	 */
	@Test
	public void testPostgresJDBCBatchStopsAtError() throws Exception {
		String url = "jdbc:postgresql://localhost:" + server.getPort() + "/" + dbName + "?user=testuser";
		try (Connection conn = DriverManager.getConnection(url);
			 Statement stmt = conn.createStatement()) {
			stmt.addBatch("INSERT INTO users VALUES (10, 'Ten', 'ten@example.com')");
			stmt.addBatch("INSERT INTO no_such_table VALUES (1)");
			stmt.addBatch("INSERT INTO users VALUES (11, 'Eleven', 'eleven@example.com')");
			assertThrows(java.sql.BatchUpdateException.class, stmt::executeBatch);
		}
		assertNull(db.tables().selectByKey("users", convex.core.data.prim.CVMLong.create(11)),
			"a statement after the failed one must not run");
	}

	/**
	 * Tests aggregation via PostgreSQL JDBC driver.
	 */
	@Test
	public void testPostgresJDBCAggregation() throws Exception {
		String url = "jdbc:postgresql://localhost:" + server.getPort() + "/" + dbName + "?user=testuser";
		try (Connection conn = DriverManager.getConnection(url);
			 Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM users")) {

			assertTrue(rs.next());
			assertEquals(2, rs.getInt("cnt"));
		}
	}

	// Helper methods

	private void sendStartupMessage(DataOutputStream out, String database, String user) throws IOException {
		byte[] dbBytes = database.getBytes(StandardCharsets.UTF_8);
		byte[] userBytes = user.getBytes(StandardCharsets.UTF_8);

		// Calculate length: 4 (length) + 4 (version) + "user\0" + user + "\0" + "database\0" + database + "\0" + "\0"
		int length = 4 + 4 + 5 + userBytes.length + 1 + 9 + dbBytes.length + 1 + 1;

		out.writeInt(length);
		out.writeInt(196608); // Version 3.0
		out.writeBytes("user");
		out.writeByte(0);
		out.write(userBytes);
		out.writeByte(0);
		out.writeBytes("database");
		out.writeByte(0);
		out.write(dbBytes);
		out.writeByte(0);
		out.writeByte(0); // terminator
		out.flush();
	}

	private void sendQuery(DataOutputStream out, String query) throws IOException {
		byte[] queryBytes = query.getBytes(StandardCharsets.UTF_8);
		int length = 4 + queryBytes.length + 1;

		out.writeByte('Q');
		out.writeInt(length);
		out.write(queryBytes);
		out.writeByte(0);
		out.flush();
	}

	private void sendPassword(DataOutputStream out, String password) throws IOException {
		byte[] pwdBytes = password.getBytes(StandardCharsets.UTF_8);
		int length = 4 + pwdBytes.length + 1;

		out.writeByte('p');
		out.writeInt(length);
		out.write(pwdBytes);
		out.writeByte(0);
		out.flush();
	}

	/** Reads messages up to and including ReadyForQuery, returning their type bytes in order */
	private String readMessageTypes(DataInputStream in) throws IOException {
		StringBuilder types = new StringBuilder();
		while (true) {
			byte type = in.readByte();
			int length = in.readInt();
			in.readFully(new byte[length - 4]);
			types.append((char) type);
			if (type == 'Z') return types.toString();
		}
	}

	private void skipToReadyForQuery(DataInputStream in) throws IOException {
		while (true) {
			byte type = in.readByte();
			int length = in.readInt();
			if (type == 'Z') { // ReadyForQuery
				in.readByte(); // status
				break;
			} else {
				byte[] content = new byte[length - 4];
				in.readFully(content);
			}
		}
	}
}
