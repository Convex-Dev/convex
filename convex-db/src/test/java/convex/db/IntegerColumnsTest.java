package convex.db;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Vectors;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMBigInteger;
import convex.core.data.prim.CVMLong;
import convex.db.calcite.ConvexType;
import convex.db.calcite.ConvexTypeSystem;
import convex.db.lattice.SQLDatabase;
import convex.db.psql.PgServer;
import convex.db.psql.PgType;
import convex.db.psql.msg.RowDescription;

/**
 * Integers beyond 64 bits in version 0 tables
 * (<a href="https://github.com/Convex-Dev/convex/issues/757">#757</a>), through JDBC and
 * the PostgreSQL wire protocol. A version 0 {@code BIGINT} column holds any CVM integer
 * and is presented as {@code NUMERIC} with scale 0; a version 0 {@code INTEGER} column is
 * presented as {@code BIGINT}; {@code SUM} over either is a {@code NUMERIC}.
 */
public class IntegerColumnsTest {

	private static final AtomicInteger DB_COUNTER = new AtomicInteger(0);

	private static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);
	private static final BigInteger MIN = BigInteger.valueOf(Long.MIN_VALUE);
	private static final BigInteger ABOVE = MAX.add(BigInteger.ONE);
	private static final BigInteger BELOW = MIN.subtract(BigInteger.ONE);
	private static final BigInteger HUGE = BigInteger.TWO.pow(200);

	private ConvexDB cdb;
	private SQLDatabase db;
	private String dbName;
	private PgServer server;

	@BeforeEach
	public void setUp() throws Exception {
		dbName = "integers_" + DB_COUNTER.getAndIncrement();
		cdb = ConvexDB.create();
		db = cdb.database(dbName);
		cdb.register(dbName);

		// Rows are written through the lattice API, which stores any CVM integer
		db.tables().createTable("big", new String[] {"id", "v"}, new ConvexType[] {ConvexType.BIGINT, ConvexType.BIGINT});
		assertTrue(db.tables().insert("big", Vectors.of(CVMLong.create(1), CVMLong.MAX_VALUE)));
		assertTrue(db.tables().insert("big", Vectors.of(CVMLong.create(2), CVMBigInteger.wrap(ABOVE))));
		assertTrue(db.tables().insert("big", Vectors.of(CVMLong.create(3), CVMLong.MIN_VALUE)));
		assertTrue(db.tables().insert("big", Vectors.of(CVMLong.create(4), CVMBigInteger.wrap(BELOW))));
		assertTrue(db.tables().insert("big", Vectors.of(CVMLong.create(5), CVMBigInteger.wrap(HUGE))));
		// A small key held as a big integer is keyed by its value
		assertTrue(db.tables().insert("big", Vectors.of(CVMBigInteger.wrap(BigInteger.valueOf(6)), CVMLong.create(6))));

		db.tables().createTable("ints", new String[] {"id", "v"}, new ConvexType[] {ConvexType.INTEGER, ConvexType.INTEGER});
		for (long id = 1; id <= 3; id++) {
			assertTrue(db.tables().insert("ints", Vectors.of(CVMLong.create(id), CVMLong.MAX_VALUE)));
		}

		server = PgServer.builder().port(0).database(dbName).build();
		server.start();
	}

	@AfterEach
	public void tearDown() {
		if (server != null && server.isRunning()) server.stop();
		if (cdb != null) cdb.unregister(dbName);
	}

	private Connection jdbc() throws SQLException {
		return DriverManager.getConnection("jdbc:convex:database=" + dbName);
	}

	private Connection pgwire() throws SQLException {
		return DriverManager.getConnection("jdbc:postgresql://localhost:" + server.getPort() + "/" + dbName + "?user=testuser");
	}

	// ========== Reported cases ==========

	@Test
	public void testSelectIntegerBeyond64BitsJdbc() throws SQLException {
		try (Connection conn = jdbc()) {
			checkSelectIntegerBeyond64Bits(conn);
		}
	}

	@Test
	public void testSelectIntegerBeyond64BitsPgwire() throws SQLException {
		try (Connection conn = pgwire()) {
			checkSelectIntegerBeyond64Bits(conn);
		}
	}

	private static void checkSelectIntegerBeyond64Bits(Connection conn) throws SQLException {
		assertEquals(List.of(new BigDecimal(ABOVE)), column(conn, "SELECT v FROM big WHERE id = 2"));
		assertEquals(List.of(new BigDecimal(HUGE)), column(conn, "SELECT v FROM big WHERE id = 5"));
	}

	@Test
	public void testSumBeyond64BitsJdbc() throws SQLException {
		try (Connection conn = jdbc()) {
			checkSumBeyond64Bits(conn);
		}
	}

	@Test
	public void testSumBeyond64BitsPgwire() throws SQLException {
		try (Connection conn = pgwire()) {
			checkSumBeyond64Bits(conn);
		}
	}

	private static void checkSumBeyond64Bits(Connection conn) throws SQLException {
		BigInteger sum = MAX.multiply(BigInteger.valueOf(3));
		assertEquals(List.of(new BigDecimal(sum)), column(conn, "SELECT SUM(v) FROM ints"));
		// Sums over a NUMERIC column are exact too
		BigInteger bigSum = MAX.add(ABOVE).add(MIN).add(BELOW).add(HUGE).add(BigInteger.valueOf(6));
		assertEquals(List.of(new BigDecimal(bigSum)), column(conn, "SELECT SUM(v) FROM big"));
		// An average of integers that divides exactly is exact
		assertEquals(List.of(new BigDecimal(MAX)), column(conn, "SELECT AVG(v) FROM ints"));
	}

	// ========== Boundaries ==========

	@Test
	public void testBoundariesJdbc() throws SQLException {
		try (Connection conn = jdbc()) {
			checkBoundaries(conn);
		}
	}

	@Test
	public void testBoundariesPgwire() throws SQLException {
		try (Connection conn = pgwire()) {
			checkBoundaries(conn);
		}
	}

	private static void checkBoundaries(Connection conn) throws SQLException {
		List<BigDecimal> expected = List.of(new BigDecimal(MAX), new BigDecimal(ABOVE),
			new BigDecimal(MIN), new BigDecimal(BELOW));
		assertEquals(expected, column(conn, "SELECT v FROM big WHERE id < 5 ORDER BY id"));
		try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT v FROM ints WHERE id = 1")) {
			assertTrue(rs.next());
			assertEquals(Long.MAX_VALUE, rs.getLong(1));
		}
	}

	// ========== Column types ==========

	@Test
	public void testColumnTypesJdbc() throws SQLException {
		try (Connection conn = jdbc(); Statement st = conn.createStatement()) {
			try (ResultSet rs = st.executeQuery("SELECT id, v FROM big")) {
				ResultSetMetaData meta = rs.getMetaData();
				for (int col = 1; col <= 2; col++) {
					assertEquals(Types.DECIMAL, meta.getColumnType(col));
					assertEquals(ConvexTypeSystem.MAX_DECIMAL_PRECISION, meta.getPrecision(col));
					assertEquals(0, meta.getScale(col));
					assertEquals(BigDecimal.class.getName(), meta.getColumnClassName(col));
				}
			}
			try (ResultSet rs = st.executeQuery("SELECT v FROM ints")) {
				assertEquals(Types.BIGINT, rs.getMetaData().getColumnType(1));
			}
			try (ResultSet rs = st.executeQuery("SELECT SUM(v), AVG(v), COUNT(*) FROM ints")) {
				ResultSetMetaData meta = rs.getMetaData();
				assertEquals(Types.DECIMAL, meta.getColumnType(1));
				assertEquals(Types.DECIMAL, meta.getColumnType(2));
				assertEquals(Types.BIGINT, meta.getColumnType(3));
			}
			try (ResultSet rs = st.executeQuery("SELECT SUM(v) FROM big")) {
				assertEquals(Types.DECIMAL, rs.getMetaData().getColumnType(1));
			}
		}
	}

	@Test
	public void testColumnTypesPgwire() throws SQLException {
		try (Connection conn = pgwire(); Statement st = conn.createStatement()) {
			try (ResultSet rs = st.executeQuery("SELECT v FROM big")) {
				assertEquals("numeric", rs.getMetaData().getColumnTypeName(1));
				assertEquals(Types.NUMERIC, rs.getMetaData().getColumnType(1));
			}
			try (ResultSet rs = st.executeQuery("SELECT v FROM ints")) {
				assertEquals("int8", rs.getMetaData().getColumnTypeName(1));
			}
			try (ResultSet rs = st.executeQuery("SELECT SUM(v) FROM ints")) {
				assertEquals("numeric", rs.getMetaData().getColumnTypeName(1));
			}
		}
	}

	@Test
	public void testRowDescription() throws SQLException {
		try (Connection conn = jdbc(); Statement st = conn.createStatement()) {
			try (ResultSet rs = st.executeQuery("SELECT v FROM big")) {
				RowDescription.Column column = RowDescription.fromMetaData(rs.getMetaData()).getColumn(0);
				assertEquals(PgType.NUMERIC, column.typeOid);
				assertEquals(-1, column.typeMod); // numeric without a precision limit
			}
			try (ResultSet rs = st.executeQuery("SELECT v FROM ints")) {
				assertEquals(PgType.INT8, RowDescription.fromMetaData(rs.getMetaData()).getColumn(0).typeOid);
			}
			try (ResultSet rs = st.executeQuery("SELECT SUM(v) FROM ints")) {
				assertEquals(PgType.NUMERIC, RowDescription.fromMetaData(rs.getMetaData()).getColumn(0).typeOid);
			}
		}
	}

	// ========== Filters and keys ==========

	@Test
	public void testFilterJdbc() throws SQLException {
		try (Connection conn = jdbc()) {
			checkFilter(conn);
		}
	}

	@Test
	public void testFilterPgwire() throws SQLException {
		try (Connection conn = pgwire()) {
			checkFilter(conn);
		}
	}

	private static void checkFilter(Connection conn) throws SQLException {
		assertEquals(ids(2), column(conn, "SELECT id FROM big WHERE v = 9223372036854775808"));
		assertEquals(ids(2, 5), column(conn, "SELECT id FROM big WHERE v > 9223372036854775807 ORDER BY id"));
		assertEquals(ids(4), column(conn, "SELECT id FROM big WHERE v < -9223372036854775808"));
		assertEquals(ids(1, 2), column(conn, "SELECT id FROM big WHERE v BETWEEN 9223372036854775807 AND 9223372036854775808 ORDER BY id"));
		assertEquals(ids(2, 4), column(conn, "SELECT id FROM big WHERE v IN (9223372036854775808, -9223372036854775809) ORDER BY id"));

		try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM big WHERE v = ?")) {
			ps.setBigDecimal(1, new BigDecimal(HUGE));
			assertEquals(ids(5), column(ps));
			ps.setBigDecimal(1, new BigDecimal(BELOW));
			assertEquals(ids(4), column(ps));
		}
	}

	@Test
	public void testKeyLookupJdbc() throws SQLException {
		try (Connection conn = jdbc()) {
			checkKeyLookup(conn);
		}
	}

	@Test
	public void testKeyLookupPgwire() throws SQLException {
		try (Connection conn = pgwire()) {
			checkKeyLookup(conn);
		}
	}

	private static void checkKeyLookup(Connection conn) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement("SELECT v FROM big WHERE id = ?")) {
			ps.setBigDecimal(1, BigDecimal.valueOf(2));
			assertEquals(List.of(new BigDecimal(ABOVE)), column(ps));
			ps.setLong(1, 5);
			assertEquals(List.of(new BigDecimal(HUGE)), column(ps));
			ps.setBigDecimal(1, BigDecimal.valueOf(6));
			assertEquals(List.of(BigDecimal.valueOf(6)), column(ps));
			// No row can have a key beyond 64 bits
			ps.setBigDecimal(1, new BigDecimal(ABOVE));
			assertEquals(List.of(), column(ps));
		}
		assertEquals(List.of(new BigDecimal(MIN)), column(conn, "SELECT v FROM big WHERE id = 3"));
		assertEquals(List.of(), column(conn, "SELECT v FROM big WHERE id = 99999999999999999999"));
	}

	@Test
	public void testKeyByValue() {
		ACell big = CVMBigInteger.wrap(BigInteger.valueOf(6));
		assertNotNull(db.tables().selectByKey("big", CVMLong.create(6)));
		assertNotNull(db.tables().selectByKey("big", big));
		assertNull(db.tables().selectByKey("big", AInteger.create(ABOVE)));
		assertThrows(IllegalArgumentException.class,
			() -> db.tables().insert("big", Vectors.of(AInteger.create(ABOVE), CVMLong.ZERO)));
	}

	// ========== Writes through SQL ==========

	@Test
	public void testInsertThroughSql() throws SQLException {
		try (Connection conn = jdbc(); Statement st = conn.createStatement()) {
			assertEquals(1, st.executeUpdate("INSERT INTO big VALUES (7, 99999999999999999999)"));
			assertEquals(1, st.executeUpdate("UPDATE big SET v = v + 1 WHERE id = 7"));
		}
		// Stored canonically, so the key is the 8-byte key of 7
		assertEquals(AInteger.parse("100000000000000000000"), db.tables().selectByKey("big", CVMLong.create(7)).get(1));
		try (Connection conn = pgwire(); PreparedStatement ps = conn.prepareStatement("INSERT INTO big VALUES (?, ?)")) {
			ps.setLong(1, 8);
			ps.setBigDecimal(2, new BigDecimal(HUGE));
			assertEquals(1, ps.executeUpdate());
		}
		assertEquals(AInteger.create(HUGE), db.tables().selectByKey("big", CVMLong.create(8)).get(1));
	}

	@Test
	public void testIntegerColumnOutOfRange() throws SQLException {
		// INTEGER columns are presented as BIGINT, which cannot hold this stored value
		assertTrue(db.tables().insert("ints", Vectors.of(CVMLong.create(4), CVMBigInteger.wrap(ABOVE))));
		try (Connection conn = jdbc()) {
			SQLException e = assertThrows(SQLException.class, () -> column(conn, "SELECT v FROM ints WHERE id = 4"));
			assertEquals("22003", sqlState(e));
		}
	}

	// ========== Helpers ==========

	private static List<BigDecimal> ids(long... ids) {
		List<BigDecimal> result = new ArrayList<>();
		for (long id : ids) result.add(BigDecimal.valueOf(id));
		return result;
	}

	/** The first column of a query's rows, as exact integers. */
	private static List<BigDecimal> column(Connection conn, String sql) throws SQLException {
		try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			return column(rs);
		}
	}

	private static List<BigDecimal> column(PreparedStatement ps) throws SQLException {
		try (ResultSet rs = ps.executeQuery()) {
			return column(rs);
		}
	}

	private static List<BigDecimal> column(ResultSet rs) throws SQLException {
		List<BigDecimal> values = new ArrayList<>();
		while (rs.next()) {
			values.add(rs.getBigDecimal(1).setScale(0));
		}
		return values;
	}

	private static String sqlState(Throwable t) {
		for (Throwable c = t; c != null; c = c.getCause()) {
			if (c instanceof SQLException e && e.getSQLState() != null) return e.getSQLState();
		}
		return null;
	}
}
