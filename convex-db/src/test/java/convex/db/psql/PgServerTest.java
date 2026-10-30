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

import convex.core.data.Blob;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBigInteger;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
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
	 * A statement the driver has named is prepared on the server's connection once,
	 * and every later execution only binds new values.
	 */
	@Test
	public void testPreparedStatementPreparedOnce() throws Exception {
		AtomicInteger prepares = new AtomicInteger();
		PgServer counting = countingServer(prepares);
		try {
			// prepareThreshold=1: the driver names the statement from its first execution on
			String url = "jdbc:postgresql://localhost:" + counting.getPort() + "/" + dbName + "?user=testuser&prepareThreshold=1";
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
			assertEquals(1, prepares.get());
		} finally {
			counting.stop();
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
	 * A statement parsed again under the same name is a new statement: the one prepared
	 * for the previous query is not reused for it. The driver sends every execution as
	 * the unnamed statement with prepareThreshold=0.
	 */
	@Test
	public void testStatementParsedAgainUnderSameName() throws Exception {
		AtomicInteger prepares = new AtomicInteger();
		PgServer counting = countingServer(prepares);
		try {
			String url = "jdbc:postgresql://localhost:" + counting.getPort() + "/" + dbName + "?user=testuser&prepareThreshold=0";
			try (Connection conn = DriverManager.getConnection(url);
				 java.sql.PreparedStatement byId = conn.prepareStatement("SELECT name FROM users WHERE id = ?");
				 java.sql.PreparedStatement byEmail = conn.prepareStatement("SELECT id FROM users WHERE email = ?")) {
				for (int i = 0; i < 3; i++) {
					byId.setLong(1, 2L);
					try (ResultSet rs = byId.executeQuery()) {
						assertTrue(rs.next());
						assertEquals("Bob", rs.getString(1));
					}
					byEmail.setString(1, "alice@example.com");
					try (ResultSet rs = byEmail.executeQuery()) {
						assertTrue(rs.next());
						assertEquals(1L, rs.getLong(1));
					}
				}
			}
			assertEquals(6, prepares.get());
		} finally {
			counting.stop();
		}
	}

	/**
	 * A table changes under statements the driver keeps on the server. One whose rows are
	 * described as before runs on; the other gets PostgreSQL's 0A000 from
	 * RevalidateCachedQuery, on which the driver prepares it again and retries. Either way
	 * the application reads the new table.
	 */
	@Test
	public void testPostgresJDBCTableChangedUnderPreparedStatement() throws Exception {
		// prepareThreshold=1: the driver names the statements from their first execution on
		String url = "jdbc:postgresql://localhost:" + server.getPort() + "/" + dbName + "?user=testuser&prepareThreshold=1";
		try (Connection conn = DriverManager.getConnection(url);
			 java.sql.PreparedStatement byId = conn.prepareStatement("SELECT name FROM users WHERE id = ?");
			 java.sql.PreparedStatement all = conn.prepareStatement("SELECT * FROM users WHERE id = ?")) {
			for (int i = 0; i < 2; i++) {
				byId.setLong(1, 2L);
				try (ResultSet rs = byId.executeQuery()) {
					assertTrue(rs.next());
					assertEquals("Bob", rs.getString(1));
				}
				all.setLong(1, 2L);
				try (ResultSet rs = all.executeQuery()) {
					assertTrue(rs.next());
					assertEquals(3, rs.getMetaData().getColumnCount());
				}
			}

			db.tables().dropTable("users");
			db.tables().createTable("users", new String[] {"id", "age", "name", "email"}, new ConvexColumnType[] {
				ConvexColumnType.of(ConvexType.INTEGER), ConvexColumnType.of(ConvexType.INTEGER),
				ConvexColumnType.varchar(50), ConvexColumnType.varchar(100)});
			db.tables().insert("users", 2L, 42L, "Robert", "robert@example.com");

			byId.setLong(1, 2L);
			try (ResultSet rs = byId.executeQuery()) {
				assertTrue(rs.next());
				assertEquals("Robert", rs.getString(1));
			}
			all.setLong(1, 2L);
			try (ResultSet rs = all.executeQuery()) {
				assertTrue(rs.next());
				assertEquals(4, rs.getMetaData().getColumnCount());
				assertEquals(42, rs.getInt("age"));
				assertEquals("Robert", rs.getString("name"));
			}
		}
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

	/** A second server on the test database whose connections count the statements prepared on them. */
	private PgServer countingServer(AtomicInteger prepares) throws Exception {
		PgServer counting = PgServer.builder()
			.port(0)
			.database(dbName)
			.connectionSupplier(() -> {
				try {
					Connection real = DriverManager.getConnection("jdbc:convex:database=" + dbName);
					return (Connection) java.lang.reflect.Proxy.newProxyInstance(
						Connection.class.getClassLoader(), new Class<?>[] { Connection.class },
						(proxy, method, args) -> {
							if (method.getName().equals("prepareStatement")) prepares.incrementAndGet();
							try {
								return method.invoke(real, args);
							} catch (java.lang.reflect.InvocationTargetException e) {
								throw e.getCause();
							}
						});
				} catch (java.sql.SQLException e) {
					throw new RuntimeException(e);
				}
			})
			.build();
		counting.start();
		return counting;
	}

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

	/** Sends one frontend message: its type, its length and its body. */
	private void sendMessage(DataOutputStream out, char type, byte[] body) throws IOException {
		out.writeByte(type);
		out.writeInt(4 + body.length);
		out.write(body);
		out.flush();
	}

	/** Parse, Describe and Sync for an unnamed statement with no declared parameter types. */
	private void parseDescribeSync(DataOutputStream out, String sql) throws IOException {
		java.io.ByteArrayOutputStream parse = new java.io.ByteArrayOutputStream();
		parse.write(0); // unnamed statement
		parse.write(sql.getBytes(StandardCharsets.UTF_8));
		parse.write(0);
		parse.write(new byte[] {0, 0}); // no parameter types
		sendMessage(out, 'P', parse.toByteArray());
		sendMessage(out, 'D', new byte[] {'S', 0});
		sendMessage(out, 'S', new byte[0]);
	}

	/**
	 * Describe answers from the statement's prepared metadata, without executing it: a scalar
	 * subquery over two rows fails only when it runs, and an INSERT described writes nothing.
	 */
	@Test
	public void testDescribeDoesNotExecute() throws Exception {
		try (Socket socket = new Socket("localhost", server.getPort())) {
			DataOutputStream out = new DataOutputStream(socket.getOutputStream());
			DataInputStream in = new DataInputStream(socket.getInputStream());
			sendStartupMessage(out, dbName, "testuser");
			skipToReadyForQuery(in);

			// ParseComplete, ParameterDescription, RowDescription, ReadyForQuery
			parseDescribeSync(out, "SELECT (SELECT name FROM users) AS x");
			assertEquals("1tTZ", readMessageTypes(in));

			// ... and NoData for a statement that returns no rows
			parseDescribeSync(out, "INSERT INTO users (id, name, email) VALUES (20, 'Gus', 'gus@example.com')");
			assertEquals("1tnZ", readMessageTypes(in));
		}
		assertFalse(userExists(20));
	}

	/**
	 * A simple query, sent as one Query message, whose statements contain semicolons in
	 * literals and comments; then DEALLOCATE of a named statement. (pgjdbc splits statements
	 * itself, so the raw protocol is what reaches the server's splitting.)
	 */
	@Test
	public void testSimpleQuerySplittingAndDeallocate() throws Exception {
		try (Socket socket = new Socket("localhost", server.getPort())) {
			DataOutputStream out = new DataOutputStream(socket.getOutputStream());
			DataInputStream in = new DataInputStream(socket.getInputStream());
			sendStartupMessage(out, dbName, "testuser");
			skipToReadyForQuery(in);

			sendQuery(out, "INSERT INTO users (id, name, email) VALUES (30, 'a; b', 'x;y@example.com'); "
				+ "INSERT INTO users (id, name, email) VALUES (31, 'c', 'd') -- comment; not a statement\n;");
			assertEquals("CCZ", readMessageTypes(in), "two statements, each completed");

			java.io.ByteArrayOutputStream parse = new java.io.ByteArrayOutputStream();
			parse.write("named_one".getBytes(StandardCharsets.UTF_8));
			parse.write(0);
			parse.write("SELECT 1".getBytes(StandardCharsets.UTF_8));
			parse.write(0);
			parse.write(new byte[] {0, 0});
			sendMessage(out, 'P', parse.toByteArray());
			sendMessage(out, 'S', new byte[0]);
			assertEquals("1Z", readMessageTypes(in));
			sendQuery(out, "DEALLOCATE named_one");
			assertEquals("CZ", readMessageTypes(in));
			sendQuery(out, "DEALLOCATE named_one");
			assertEquals("EZ", readMessageTypes(in), "a second DEALLOCATE finds no statement");
		}
		try (Connection conn = DriverManager.getConnection(pgUrl());
			 Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT name FROM users WHERE id = 30")) {
			assertTrue(rs.next());
			assertEquals("a; b", rs.getString(1));
		}
		assertTrue(userExists(31));
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

	// ========== Binary result formats ==========

	/**
	 * A prepared SELECT executed more often than pgjdbc's prepare threshold: the driver then
	 * names the statement and asks for binary results for the integer column, which must be
	 * encoded as the RowDescription announced it.
	 */
	@Test
	public void testPreparedSelectPastThresholdGetsBinaryResults() throws Exception {
		db.tables().createTable("reused", new String[]{"id", "name"},
			new ConvexColumnType[]{ConvexColumnType.of(ConvexType.INTEGER), ConvexColumnType.varchar(50)});
		int rowCount = 40;
		for (long i = 0; i < rowCount; i++) db.tables().insert("reused", i, "row-" + i);

		String url = "jdbc:postgresql://localhost:" + server.getPort() + "/" + dbName + "?user=testuser";
		try (Connection conn = DriverManager.getConnection(url);
			 java.sql.PreparedStatement sel = conn.prepareStatement("SELECT id, name FROM reused WHERE id = ?")) {
			for (int i = 0; i < rowCount; i++) {
				sel.setInt(1, i);
				try (ResultSet rs = sel.executeQuery()) {
					assertTrue(rs.next(), "row " + i);
					assertEquals(i, rs.getInt("id"));
					assertEquals("row-" + i, rs.getString("name"));
					assertFalse(rs.next());
				}
			}
		}
	}

	/**
	 * Each type pgjdbc asks for in binary once a statement is named, read back through the
	 * driver's own decoders. With prepareThreshold=1 the second execution is binary.
	 */
	@Test
	public void testBinaryResultTypes() throws Exception {
		db.tables().createTable("typed", new String[]{"id", "big", "dbl", "flag", "bin", "ts"},
			new ConvexColumnType[]{ConvexColumnType.of(ConvexType.INTEGER), ConvexColumnType.of(ConvexType.BIGINT),
				ConvexColumnType.of(ConvexType.DOUBLE), ConvexColumnType.of(ConvexType.BOOLEAN),
				ConvexColumnType.of(ConvexType.VARBINARY), ConvexColumnType.of(ConvexType.TIMESTAMP)});
		java.math.BigInteger big = java.math.BigInteger.TWO.pow(70).negate();
		byte[] bin = {1, 2, (byte) 0xff};
		long millis = 1_700_000_000_123L;
		db.tables().insert("typed", Vectors.of(CVMLong.create(1), CVMBigInteger.wrap(big), CVMDouble.create(-2.5),
			CVMBool.TRUE, Blob.wrap(bin), CVMLong.create(millis)));

		String url = "jdbc:postgresql://localhost:" + server.getPort() + "/" + dbName + "?user=testuser&prepareThreshold=1";
		try (Connection conn = DriverManager.getConnection(url);
			 java.sql.PreparedStatement sel = conn.prepareStatement("SELECT id, big, dbl, flag, bin, ts FROM typed WHERE id = ?")) {
			for (int pass = 0; pass < 2; pass++) {
				sel.setInt(1, 1);
				try (ResultSet rs = sel.executeQuery()) {
					assertTrue(rs.next(), "pass " + pass);
					assertEquals(1, rs.getInt("id"));
					assertEquals(0, new java.math.BigDecimal(big).compareTo(rs.getBigDecimal("big")));
					assertEquals(-2.5, rs.getDouble("dbl"), 0.0);
					assertTrue(rs.getBoolean("flag"));
					assertArrayEquals(bin, rs.getBytes("bin"));
					assertEquals(millis, rs.getTimestamp("ts").getTime());
					assertFalse(rs.next());
				}
			}
		}
	}

	// ========== Transaction control ==========

	private String pgUrl() {
		return "jdbc:postgresql://localhost:" + server.getPort() + "/" + dbName + "?user=testuser";
	}

	private boolean userExists(int id) throws Exception {
		try (Connection conn = DriverManager.getConnection(pgUrl());
			 Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT name FROM users WHERE id = " + id)) {
			return rs.next();
		}
	}

	/** setAutoCommit(false) sends BEGIN, commit() sends COMMIT: another connection sees the row. */
	@Test
	public void testTransactionCommitIsVisibleElsewhere() throws Exception {
		try (Connection conn = DriverManager.getConnection(pgUrl())) {
			conn.setAutoCommit(false);
			try (Statement stmt = conn.createStatement()) {
				stmt.execute("INSERT INTO users (id, name, email) VALUES (10, 'Dave', 'dave@example.com')");
			}
			assertFalse(userExists(10), "not visible before commit");
			conn.commit();
		}
		assertTrue(userExists(10));
	}

	@Test
	public void testTransactionRollbackDiscardsTheWrite() throws Exception {
		try (Connection conn = DriverManager.getConnection(pgUrl())) {
			conn.setAutoCommit(false);
			try (Statement stmt = conn.createStatement()) {
				stmt.execute("INSERT INTO users (id, name, email) VALUES (11, 'Erin', 'erin@example.com')");
			}
			conn.rollback();
		}
		assertFalse(userExists(11));
	}

	/**
	 * The status each ReadyForQuery reports, as pgjdbc records it: open in a block, failed
	 * after an error there, with every later statement refused with 25P02 until ROLLBACK.
	 */
	@Test
	public void testTransactionStatus() throws Exception {
		try (Connection conn = DriverManager.getConnection(pgUrl()); Statement stmt = conn.createStatement()) {
			org.postgresql.core.BaseConnection pg = conn.unwrap(org.postgresql.core.BaseConnection.class);
			assertEquals(org.postgresql.core.TransactionState.IDLE, pg.getTransactionState());

			stmt.execute("BEGIN");
			assertEquals(org.postgresql.core.TransactionState.OPEN, pg.getTransactionState());
			stmt.execute("INSERT INTO users (id, name, email) VALUES (12, 'Fay', 'fay@example.com')");

			assertThrows(java.sql.SQLException.class, () -> stmt.execute("SELECT * FROM no_such_table"));
			assertEquals(org.postgresql.core.TransactionState.FAILED, pg.getTransactionState());
			java.sql.SQLException refused = assertThrows(java.sql.SQLException.class, () -> stmt.execute("SELECT 1"));
			assertEquals("25P02", refused.getSQLState());

			stmt.execute("ROLLBACK");
			assertEquals(org.postgresql.core.TransactionState.IDLE, pg.getTransactionState());
			assertFalse(userExists(12));

			java.sql.SQLException savepoint = assertThrows(java.sql.SQLException.class, () -> stmt.execute("SAVEPOINT a"));
			assertEquals("0A000", savepoint.getSQLState());
		}
	}
}
