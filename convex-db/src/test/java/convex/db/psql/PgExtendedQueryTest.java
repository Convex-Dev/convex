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
import convex.db.lattice.RowLWW;
import convex.db.lattice.SQLDatabase;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * Tests the extended query protocol message by message, driving the handler through an
 * EmbeddedChannel. Each response is summarised as its message types, with the values of
 * DataRows in brackets and the SQLSTATE of ErrorResponses in parentheses: "12D[Bob]CZ".
 */
public class PgExtendedQueryTest {

	private static final AtomicInteger DB_COUNTER = new AtomicInteger(0);
	private ConvexDB cdb;
	private SQLDatabase db;
	private String dbName;
	private EmbeddedChannel channel;

	@BeforeEach
	public void setUp() {
		dbName = "pgext_" + DB_COUNTER.getAndIncrement();
		cdb = ConvexDB.create(RowLWW::merge);
		db = cdb.database(dbName);
		cdb.register(dbName);

		db.tables().createTable("users", new String[]{"id", "name", "email"}, new ConvexColumnType[] {
			ConvexColumnType.of(ConvexType.INTEGER), ConvexColumnType.varchar(50), ConvexColumnType.varchar(100)});
		db.tables().insert("users", 1L, "Alice", "alice@example.com");
		db.tables().insert("users", 2L, "Bob", "bob@example.com");
		db.tables().createTable("items", new String[]{"id", "price", "active"}, new ConvexColumnType[] {
			ConvexColumnType.of(ConvexType.INTEGER), ConvexColumnType.of(ConvexType.DOUBLE), ConvexColumnType.of(ConvexType.BOOLEAN)});

		channel = new EmbeddedChannel(new PgProtocolHandler(() -> {
			try {
				return DriverManager.getConnection("jdbc:convex:database=" + dbName);
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

	// ========== Helpers ==========

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
				if (type == 'E') sb.append('(').append(errorField(body, 'C')).append(')');
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
