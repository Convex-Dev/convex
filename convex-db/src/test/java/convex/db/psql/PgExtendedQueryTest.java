package convex.db.psql;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.db.ConvexDB;
import convex.db.calcite.ConvexColumnType;
import convex.db.calcite.ConvexType;
import convex.db.jdbc.IdentifierPolicy;
import convex.db.lattice.RowLWW;
import convex.db.lattice.SQLDatabase;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * Tests the extended query protocol message by message, driving the handler through an
 * EmbeddedChannel. Each response is summarised as its message types, with the values of
 * DataRows in brackets and the SQLSTATE of ErrorResponses in parentheses, followed by the
 * routine when one is given: "12D[Bob]CZ".
 */
public class PgExtendedQueryTest {

	private static final AtomicInteger DB_COUNTER = new AtomicInteger(0);
	private ConvexDB cdb;
	private SQLDatabase db;
	private String dbName;
	private EmbeddedChannel channel;
	/** JDBC statements prepared on the handler's connection, and how many of them are still open */
	private final AtomicInteger prepares = new AtomicInteger();
	private final AtomicInteger openStatements = new AtomicInteger();

	@BeforeEach
	public void setUp() {
		dbName = "pgext_" + DB_COUNTER.getAndIncrement();
		cdb = ConvexDB.create(RowLWW::merge);
		db = cdb.database(dbName);
		IdentifierPolicy.LOWER.applyTo(db);
		cdb.register(dbName);

		db.tables().createTable("users", new String[]{"id", "name", "email"}, new ConvexColumnType[] {
			ConvexColumnType.of(ConvexType.INTEGER), ConvexColumnType.varchar(50), ConvexColumnType.varchar(100)});
		db.tables().insert("users", 1L, "Alice", "alice@example.com");
		db.tables().insert("users", 2L, "Bob", "bob@example.com");
		db.tables().createTable("items", new String[]{"id", "price", "active"}, new ConvexColumnType[] {
			ConvexColumnType.of(ConvexType.INTEGER), ConvexColumnType.of(ConvexType.DOUBLE), ConvexColumnType.of(ConvexType.BOOLEAN)});

		channel = new EmbeddedChannel(new PgProtocolHandler(() -> {
			try {
				return tracked(DriverManager.getConnection("jdbc:convex:database=" + dbName));
			} catch (SQLException e) {
				throw new RuntimeException(e);
			}
		}, null));
		channel.writeInbound(new PgMessageDecoder.StartupMessage(3, 0, Map.of("user", "testuser", "database", dbName)));
		responses();
	}

	@AfterEach
	public void tearDown() {
		channel.finishAndReleaseAll();
		cdb.unregister(dbName);
	}

	// ========== Text format parameters ==========

	@Test
	public void testTextIntegerParameter() {
		assertEquals("12D[Bob]CZ", query("SELECT name FROM users WHERE id = $1", PgType.INT8, "2"));
		assertEquals("12D[Bob]CZ", query("SELECT name FROM users WHERE id = $1", PgType.INT4, " 2 "));
		assertEquals("12D[Bob]CZ", query("SELECT name FROM users WHERE id > $1", PgType.INT8, "1"));
	}

	@Test
	public void testUndeclaredTextParameterTakesInferredType() {
		assertEquals("12D[Bob]CZ", query("SELECT name FROM users WHERE id = $1", 0, "2"));
		assertEquals("12D[Bob]CZ", query("SELECT name FROM users WHERE id = $1", PgType.UNKNOWN, "2"));
		assertEquals("12D[2]CZ", query("SELECT id FROM users WHERE name = $1", 0, "Bob"));

		assertEquals("12CZ", execute("INSERT INTO users VALUES ($1, $2, $3)", new int[] {0, 0, 0}, "5", "Eve", "eve@example.com"));
		assertEquals(Vectors.of(5L, "Eve", "eve@example.com"), db.tables().selectByKey("users", CVMLong.create(5)));

		assertEquals("12CZ", execute("INSERT INTO items VALUES ($1, $2, $3)", new int[] {0, 0, 0}, "1", "2.5", "t"));
		assertEquals(Vectors.of(CVMLong.create(1), CVMDouble.create(2.5), CVMBool.TRUE), db.tables().selectByKey("items", CVMLong.create(1)));
		assertEquals("12D[1]CZ", query("SELECT id FROM items WHERE active = $1", 0, "yes"));
		assertEquals("12D[1]CZ", query("SELECT id FROM items WHERE price > $1", 0, "2.25"));
	}

	@Test
	public void testInvalidTextParameter() {
		assertEquals("12E(22P02)Z", query("SELECT name FROM users WHERE id = $1", PgType.INT8, "not-a-number"));
		assertEquals("12E(22P02)Z", query("SELECT name FROM users WHERE id = $1", 0, "not-a-number"));
		assertEquals("12E(22P02)Z", query("SELECT name FROM users WHERE id = $1", PgType.INT8, "2.5"));
		assertEquals("12E(22P02)Z", query("SELECT id FROM items WHERE price > $1", PgType.FLOAT8, "1d"));
		assertEquals("12E(22P02)Z", query("SELECT id FROM items WHERE active = $1", PgType.BOOL, "maybe"));
		assertEquals("12E(22003)Z", query("SELECT name FROM users WHERE id = $1", PgType.INT2, "70000"));
		assertEquals("12E(22003)Z", query("SELECT name FROM users WHERE id = $1", PgType.INT8, "9223372036854775808"));
		assertEquals("12E(22003)Z", query("SELECT id FROM items WHERE price > $1", PgType.FLOAT8, "1e400"));
	}

	@Test
	public void testTextParsing() throws SQLException {
		assertEquals(0xffffffffL, bound(PgType.OID, "4294967295"));
		assertEquals(-5L, bound(PgType.INT4, "-5"));
		assertEquals(Double.NEGATIVE_INFINITY, bound(PgType.FLOAT8, "-Infinity"));
		assertEquals((double) 0.1f, bound(PgType.FLOAT4, "0.1"));
		assertEquals(false, bound(PgType.BOOL, "OFF"));
		assertEquals("2", bound(PgType.VARCHAR, "2"));
	}

	// ========== Errors ==========

	/** After an error, every message up to Sync is discarded, so a later statement never runs. */
	@Test
	public void testErrorDiscardsUntilSync() {
		channel.writeInbound(new PgMessageDecoder.Parse("", "SELECT name FROM users WHERE id = $1", new int[] {PgType.INT8}));
		bind("", "not-a-number");
		channel.writeInbound(new PgMessageDecoder.Execute("", 0));
		channel.writeInbound(new PgMessageDecoder.Parse("", "INSERT INTO users VALUES ($1, $2, $3)", new int[] {0, 0, 0}));
		bind("", "7", "Mallory", "mallory@example.com");
		channel.writeInbound(new PgMessageDecoder.Execute("", 0));
		channel.writeInbound(PgMessageDecoder.Flush.INSTANCE);
		channel.writeInbound(PgMessageDecoder.Sync.INSTANCE);

		assertEquals("12E(22P02)Z", responses(), "nothing between the error and Sync gets a response");
		assertNull(db.tables().selectByKey("users", CVMLong.create(7)), "a statement after the error must not run");

		// Sync ends the error state
		assertEquals("12D[Bob]CZ", query("SELECT name FROM users WHERE id = $1", PgType.INT8, "2"));
	}

	// ========== Prepared statement reuse ==========

	/** A failed execution, in binding or in executing, leaves the statement working after Sync. */
	@Test
	public void testStatementWorksAfterError() {
		parse("s1", "SELECT 10 / $1 FROM users WHERE id = 2", PgType.INT8);
		assertEquals("2D[2]CZ", run("s1", "5"));
		assertEquals("2E(XX000)Z", run("s1", "0"), "division by zero fails in executing");
		assertEquals("2D[5]CZ", run("s1", "2"));
		assertEquals("2E(22P02)Z", run("s1", "abc"), "invalid text fails in binding");
		assertEquals("2D[10]CZ", run("s1", "1"));
		assertEquals(1, prepares.get(), "every execution uses the statement's one JDBC statement");
	}

	/** A Parse under the name of an open statement replaces it, and Close closes it. */
	@Test
	public void testParseReplacesStatement() {
		parse("s1", "SELECT name FROM users WHERE id = $1", PgType.INT8);
		assertEquals("2D[Bob]CZ", run("s1", "2"));
		parse("s1", "SELECT email FROM users WHERE id = $1", PgType.INT8);
		assertEquals("2D[bob@example.com]CZ", run("s1", "2"));
		assertEquals("2D[alice@example.com]CZ", run("s1", "1"));
		assertEquals(2, prepares.get());
		assertEquals(1, openStatements.get(), "the replaced statement's JDBC statement is closed");

		channel.writeInbound(new PgMessageDecoder.Close((byte) 'S', "s1"));
		channel.writeInbound(PgMessageDecoder.Sync.INSTANCE);
		assertEquals("3Z", responses());
		assertEquals(0, openStatements.get());
	}

	/** A later execution binds only its own values: a parameter it does not supply is not the previous one. */
	@Test
	public void testParametersClearedBetweenExecutions() {
		parse("s1", "SELECT name FROM users WHERE id = $1 OR email = $2", PgType.INT8, PgType.VARCHAR);
		assertEquals("2D[Alice]D[Bob]CZ", run("s1", "1", "bob@example.com"));
		assertEquals("2E(42000)Z", run("s1", "1"), "the second parameter is unbound, not bob@example.com");
		assertEquals("2D[Alice]D[Bob]CZ", run("s1", "2", "alice@example.com"));
	}

	/** Every JDBC statement kept for a statement is closed when the connection ends, also after an error. */
	@Test
	public void testConnectionEndClosesStatements() {
		parse("s1", "SELECT name FROM users WHERE id = $1", PgType.INT8);
		parse("s2", "SELECT 10 / $1 FROM users WHERE id = 2", PgType.INT8);
		assertEquals("2D[Bob]CZ", run("s1", "2"));
		assertEquals("2E(XX000)Z", run("s2", "0"));
		assertEquals("12D[Alice]CZ", query("SELECT name FROM users WHERE id = $1", PgType.INT8, "1"));
		assertEquals(3, openStatements.get());

		channel.close();
		assertEquals(0, openStatements.get());
	}

	// ========== A table changed under a kept statement ==========

	/** A kept statement whose table changed is prepared again, and runs on if its rows are described alike. */
	@Test
	public void testStatementPreparedAgainAfterTableChange() {
		parse("s1", "SELECT name FROM users WHERE id = $1", PgType.INT8);
		assertEquals("2D[Bob]CZ", run("s1", "2"));

		// The name column, now in another position
		recreateUsers(new String[] {"id", "email", "name"}, ConvexColumnType.varchar(100), ConvexColumnType.varchar(50));
		db.tables().insert("users", 2L, "robert@example.com", "Robert");
		assertEquals("2D[Robert]CZ", run("s1", "2"));
		assertEquals(2, prepares.get());
		assertEquals(1, openStatements.get(), "the old JDBC statement is closed");
	}

	/** Rows of another description fail with 0A000, as in PostgreSQL, until the statement is parsed again. */
	@Test
	public void testResultTypeChangedUntilParsedAgain() {
		parse("s1", "SELECT * FROM users WHERE id = $1", PgType.INT8);
		assertEquals("2D[2, Bob, bob@example.com]CZ", run("s1", "2"));

		recreateUsers(new String[] {"id", "age", "name", "email"},
			ConvexColumnType.of(ConvexType.INTEGER), ConvexColumnType.varchar(50), ConvexColumnType.varchar(100));
		db.tables().insert("users", 2L, 42L, "Bob", "bob@example.com");
		assertEquals("2E(0A000 RevalidateCachedQuery)Z", run("s1", "2"));
		assertEquals("2E(0A000 RevalidateCachedQuery)Z", run("s1", "2"), "the statement keeps failing");
		assertEquals(1, openStatements.get(), "only the old JDBC statement stays open");

		parse("s1", "SELECT * FROM users WHERE id = $1", PgType.INT8);
		assertEquals("2D[2, 42, Bob, bob@example.com]CZ", run("s1", "2"));
		assertEquals(1, openStatements.get());
	}

	/** A join fails on its changed table before it sends any row, so it is prepared again cleanly. */
	@Test
	public void testJoinPreparedAgainAfterTableChange() {
		db.tables().insert("items", 2L, 2.5, true);
		parse("s1", "SELECT u.name, i.price FROM users u JOIN items i ON u.id = i.id WHERE i.price > $1", PgType.FLOAT8);
		assertEquals("2D[Bob, 2.5]CZ", run("s1", "0"));

		assertTrue(db.tables().dropTable("items"));
		db.tables().createTable("items", new String[]{"id", "active", "price"}, new ConvexColumnType[] {
			ConvexColumnType.of(ConvexType.INTEGER), ConvexColumnType.of(ConvexType.BOOLEAN), ConvexColumnType.of(ConvexType.DOUBLE)});
		db.tables().insert("items", 1L, true, 1.5);
		db.tables().insert("items", 2L, false, 3.5);
		assertEquals("2D[Alice, 1.5]D[Bob, 3.5]CZ", run("s1", "0"));
		assertEquals(2, prepares.get());
	}

	/** A kept statement fails while its table is dropped, and works again once the table is back as it was. */
	@Test
	public void testStatementAfterTableDropped() {
		parse("s1", "SELECT name FROM users WHERE id = $1", PgType.INT8);
		assertEquals("2D[Bob]CZ", run("s1", "2"));

		assertTrue(db.tables().dropTable("users"));
		assertEquals("2E(42000)Z", run("s1", "2"), "preparing the statement again finds no table");

		createUsers(new String[] {"id", "name", "email"}, ConvexColumnType.varchar(50), ConvexColumnType.varchar(100));
		db.tables().insert("users", 2L, "Robert", "robert@example.com");
		assertEquals("2D[Robert]CZ", run("s1", "2"));
		assertEquals(1, prepares.get(), "the table has the binding the kept statement was planned for");
	}

	// ========== Helpers ==========

	/** Drops the users table and creates it again with an integer id followed by the given columns. */
	private void recreateUsers(String[] columns, ConvexColumnType... types) {
		assertTrue(db.tables().dropTable("users"));
		createUsers(columns, types);
	}

	/** Creates the users table with an integer id followed by the given columns. */
	private void createUsers(String[] columns, ConvexColumnType... types) {
		ConvexColumnType[] all = new ConvexColumnType[types.length + 1];
		all[0] = ConvexColumnType.of(ConvexType.INTEGER);
		System.arraycopy(types, 0, all, 1, types.length);
		assertTrue(db.tables().createTable("users", columns, all));
	}

	/** Parses a statement, then syncs. */
	private void parse(String name, String sql, int... types) {
		channel.writeInbound(new PgMessageDecoder.Parse(name, sql, types));
		channel.writeInbound(PgMessageDecoder.Sync.INSTANCE);
		assertEquals("1Z", responses());
	}

	/** Binds and executes a parsed statement with text parameters, then syncs. */
	private String run(String statement, String... values) {
		bind(statement, values);
		channel.writeInbound(new PgMessageDecoder.Execute("", 0));
		channel.writeInbound(PgMessageDecoder.Sync.INSTANCE);
		return responses();
	}

	/** Parses, binds and executes a query with one text parameter, then syncs. */
	private String query(String sql, int type, String value) {
		return execute(sql, new int[] {type}, value);
	}

	/** Parses, binds and executes a statement with text parameters, then syncs. */
	private String execute(String sql, int[] types, String... values) {
		channel.writeInbound(new PgMessageDecoder.Parse("", sql, types));
		bind("", values);
		channel.writeInbound(new PgMessageDecoder.Execute("", 0));
		channel.writeInbound(PgMessageDecoder.Sync.INSTANCE);
		return responses();
	}

	private void bind(String statement, String... values) {
		byte[][] bytes = new byte[values.length][];
		for (int i = 0; i < values.length; i++) {
			bytes[i] = values[i].getBytes(StandardCharsets.UTF_8);
		}
		channel.writeInbound(new PgMessageDecoder.Bind("", statement, new short[] {0}, bytes, new short[0]));
	}

	/** The value a text parameter of the given type binds to JDBC. */
	private static Object bound(int oid, String text) throws SQLException {
		Object[] value = new Object[1];
		java.sql.PreparedStatement statement = (java.sql.PreparedStatement) java.lang.reflect.Proxy.newProxyInstance(
			PgExtendedQueryTest.class.getClassLoader(), new Class<?>[] {java.sql.PreparedStatement.class},
			(proxy, method, args) -> {
				value[0] = args[1];
				return null;
			});
		PgProtocolHandler.bindTextParameter(statement, 1, text, oid);
		return value[0];
	}

	/** The connection, counting the statements prepared on it and keeping track of which are closed. */
	private java.sql.Connection tracked(java.sql.Connection connection) {
		return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
			PgExtendedQueryTest.class.getClassLoader(), new Class<?>[] {java.sql.Connection.class},
			(proxy, method, args) -> {
				Object result = invoke(connection, method, args);
				if (!method.getName().equals("prepareStatement")) return result;
				prepares.incrementAndGet();
				openStatements.incrementAndGet();
				java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
				return java.lang.reflect.Proxy.newProxyInstance(
					PgExtendedQueryTest.class.getClassLoader(), new Class<?>[] {java.sql.PreparedStatement.class},
					(p, m, a) -> {
						if (m.getName().equals("close") && closed.compareAndSet(false, true)) openStatements.decrementAndGet();
						return invoke(result, m, a);
					});
			});
	}

	private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
		try {
			return method.invoke(target, args);
		} catch (java.lang.reflect.InvocationTargetException e) {
			throw e.getCause();
		}
	}

	/** Reads and summarises everything the handler has written since the last call. */
	private String responses() {
		StringBuilder sb = new StringBuilder();
		Object msg;
		while ((msg = channel.readOutbound()) != null) {
			ByteBuf buf = (ByteBuf) msg;
			while (buf.isReadable()) {
				char type = (char) buf.readByte();
				byte[] body = new byte[buf.readInt() - 4];
				buf.readBytes(body);
				sb.append(type);
				if (type == 'D') sb.append(dataRow(body));
				if (type == 'E') {
					String routine = errorField(body, 'R');
					sb.append('(').append(errorField(body, 'C')).append((routine == null) ? "" : " " + routine).append(')');
				}
			}
			buf.release();
		}
		return sb.toString();
	}

	private static List<String> dataRow(byte[] body) {
		ByteBuffer buf = ByteBuffer.wrap(body);
		int count = buf.getShort();
		List<String> values = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			int length = buf.getInt();
			if (length < 0) {
				values.add(null);
			} else {
				byte[] value = new byte[length];
				buf.get(value);
				values.add(new String(value, StandardCharsets.UTF_8));
			}
		}
		return values;
	}

	/** A field of an ErrorResponse: a type byte and a null-terminated string per field. */
	private static String errorField(byte[] body, char field) {
		int i = 0;
		while (i < body.length && body[i] != 0) {
			char type = (char) body[i++];
			int end = i;
			while (body[end] != 0) end++;
			if (type == field) return new String(body, i, end - i, StandardCharsets.UTF_8);
			i = end + 1;
		}
		return null;
	}
}
