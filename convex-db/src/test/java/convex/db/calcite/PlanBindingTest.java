package convex.db.calcite;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.db.ConvexDB;
import convex.db.lattice.RowLWW;
import convex.db.lattice.SQLDatabase;

/**
 * A prepared statement's plan is built for its tables' binding, their schema and format
 * version (CAD039). It reads and writes rows only while a table still has that binding,
 * and fails once the table is dropped or changes, rather than misread its rows.
 */
public class PlanBindingTest {

	private static final AtomicInteger DB_COUNTER = new AtomicInteger(0);

	private ConvexDB cdb;
	private SQLDatabase db;
	private Connection conn;
	private String dbName;

	@BeforeEach
	void setUp() throws Exception {
		dbName = "planbinding_" + DB_COUNTER.getAndIncrement();
		cdb = ConvexDB.create(RowLWW::merge);
		db = cdb.database(dbName);
		cdb.register(dbName);
		conn = DriverManager.getConnection("jdbc:convex:database=" + dbName);
		execute("CREATE TABLE t (id INTEGER, v VARCHAR)");
		execute("INSERT INTO t VALUES (1, 'one')");
	}

	@AfterEach
	void tearDown() throws Exception {
		if (conn != null) conn.close();
		if (cdb != null) cdb.unregister(dbName);
	}

	@Test
	void testQueryFailsAfterTableRecreated() throws SQLException {
		PreparedStatement scan = prepare("SELECT * FROM t WHERE id > ?", 0);
		PreparedStatement byKey = prepare("SELECT * FROM t WHERE id = ?", 1);
		assertEquals(List.of("[1, one]"), rows(scan));
		assertEquals(List.of("[1, one]"), rows(byKey));

		execute("DROP TABLE t");
		execute("CREATE TABLE t (id INTEGER, n INTEGER, v VARCHAR)");
		execute("INSERT INTO t VALUES (1, 7, 'seven')");

		assertStale(() -> rows(scan));
		assertStale(() -> rows(byKey));
		assertEquals(List.of("[1, 7, seven]"), rows(prepare("SELECT * FROM t WHERE id = ?", 1)),
			"a statement prepared now reads the new columns");
	}

	@Test
	void testQueryFailsAfterTableDropped() throws SQLException {
		PreparedStatement scan = prepare("SELECT * FROM t WHERE id > ?", 0);
		PreparedStatement count = prepare("SELECT COUNT(*) FROM t WHERE id > ?", 0);
		assertEquals(List.of("[1]"), rows(count));

		// SQL keeps unquoted names in upper case
		assertTrue(db.tables().dropTable("T"));
		assertStale(() -> rows(scan));
		assertStale(() -> rows(count));
	}

	/** A change merged in from another writer has the same effect as DDL on this connection. */
	@Test
	void testWritesFailAfterTableRecreated() throws SQLException {
		PreparedStatement insert = conn.prepareStatement("INSERT INTO t VALUES (?, ?)");
		PreparedStatement update = conn.prepareStatement("UPDATE t SET v = ? WHERE id = ?");
		PreparedStatement delete = conn.prepareStatement("DELETE FROM t WHERE id = ?");

		assertTrue(db.tables().dropTable("T"));
		db.tables().createTable("T", new String[] {"ID", "W"}, new ConvexColumnType[] {
			ConvexColumnType.of(ConvexType.INTEGER), ConvexColumnType.varchar(10)});
		db.tables().insert("T", 1L, "uno");

		insert.setLong(1, 2);
		insert.setString(2, "two");
		assertStale(insert::executeUpdate);
		update.setString(1, "changed");
		update.setLong(2, 1);
		assertStale(update::executeUpdate);
		delete.setLong(1, 1);
		assertStale(delete::executeUpdate);

		assertNull(db.tables().selectByKey("T", CVMLong.create(2)), "the insert wrote nothing");
		assertEquals(Vectors.of(1L, "uno"), db.tables().selectByKey("T", CVMLong.create(1)),
			"the update and delete wrote nothing");
	}

	/** Row writes leave the binding alone, and so does re-creating a table with the same columns. */
	@Test
	void testStatementKeepsWorkingUnderSameBinding() throws SQLException {
		PreparedStatement scan = prepare("SELECT * FROM t WHERE id > ?", 0);
		PreparedStatement insert = conn.prepareStatement("INSERT INTO t VALUES (?, ?)");
		insert.setLong(1, 2);
		insert.setString(2, "two");
		assertEquals(1, insert.executeUpdate());
		assertEquals(List.of("[1, one]", "[2, two]"), rows(scan));

		execute("DROP TABLE t");
		execute("CREATE TABLE t (id INTEGER, v VARCHAR)");
		execute("INSERT INTO t VALUES (3, 'three')");
		assertEquals(List.of("[3, three]"), rows(scan));
		insert.setLong(1, 4);
		insert.setString(2, "four");
		assertEquals(1, insert.executeUpdate());
		assertEquals(List.of("[3, three]", "[4, four]"), rows(scan));
	}

	/**
	 * A plan's binding is taken a moment after its row type was validated. If the table
	 * changed in between, the binding is refused rather than paired with the old row type.
	 */
	@Test
	void testBindRefusesTableChangedSinceValidation() {
		ConvexTable table = new ConvexSchema(db, dbName).getConvexTable("T");
		RelDataTypeFactory typeFactory = new JavaTypeFactoryImpl(ConvexTypeSystem.INSTANCE);
		RelOptTable validated = validatedAs(table.getRowType(typeFactory));
		assertEquals(db.tables().getBinding("T"), table.bind(validated, typeFactory));

		assertTrue(db.tables().dropTable("T"));
		db.tables().createTable("T", new String[] {"ID", "N", "V"}, new ConvexColumnType[] {
			ConvexColumnType.of(ConvexType.INTEGER), ConvexColumnType.of(ConvexType.INTEGER), ConvexColumnType.of(ConvexType.VARCHAR)});
		assertThrows(StalePlanException.class, () -> table.bind(validated, typeFactory));

		assertTrue(db.tables().dropTable("T"));
		assertThrows(StalePlanException.class, () -> table.bind(validated, typeFactory));
	}

	// ========== Helpers ==========

	@Test
	void testPreparedReadsFollowTransaction() throws SQLException {
		try (PreparedStatement scan = prepare("SELECT * FROM t WHERE id > ?", 0);
			 PreparedStatement key = prepare("SELECT * FROM t WHERE id = ?", 2);
			 Connection other = cdb.getConnection(dbName)) {
			conn.setAutoCommit(false);
			execute("INSERT INTO t VALUES (2, 'two')");
			try (Statement s = other.createStatement()) {
				s.executeUpdate("INSERT INTO t VALUES (3, 'three')");
			}
			assertEquals(List.of("[1, one]", "[2, two]"), rows(scan));
			assertEquals(List.of("[2, two]"), rows(key));
			conn.rollback();
			assertEquals(List.of("[1, one]", "[3, three]"), rows(scan));
			assertEquals(List.of(), rows(key));
			conn.setAutoCommit(true);
			execute("INSERT INTO t VALUES (2, 'live')");
			assertEquals(List.of("[2, live]"), rows(key));
		}
	}

	@Test
	void testPreparedWritesFollowCommitAndRollback() throws SQLException {
		conn.setAutoCommit(false);
		try (PreparedStatement insert = conn.prepareStatement("INSERT INTO t VALUES (?, ?)");
			 PreparedStatement update = conn.prepareStatement("UPDATE t SET v = ? WHERE id = ?");
			 PreparedStatement delete = prepare("DELETE FROM t WHERE id = ?", 1)) {
			for (int id = 2; id <= 4; id++) {
				insert.setLong(1, id);
				insert.setString(2, "row" + id);
				assertEquals(1, insert.executeUpdate());
				conn.commit();
			}
			update.setString(1, "changed");
			update.setLong(2, 2);
			assertEquals(1, update.executeUpdate());
			conn.commit();
			assertEquals(1, delete.executeUpdate());
			conn.rollback();
			assertEquals(1, delete.executeUpdate());
			conn.setAutoCommit(true);
			assertEquals(List.of("[2, changed]", "[3, row3]", "[4, row4]"), rows(prepare("SELECT * FROM t WHERE id > ?", 0)));
			assertEquals(Vectors.of(2L, "changed"), db.tables().selectByKey("T", CVMLong.create(2)));
			assertNull(db.tables().selectByKey("T", CVMLong.ONE));
		}
	}

	@Test
	void testPreparedPlanStillChecksSchemaAfterCommit() throws SQLException {
		conn.setAutoCommit(false);
		try (PreparedStatement scan = prepare("SELECT * FROM t WHERE id > ?", 0);
			 PreparedStatement insert = conn.prepareStatement("INSERT INTO t VALUES (2, 'two')")) {
			conn.commit();
			execute("DROP TABLE t");
			execute("CREATE TABLE t (id INTEGER, n INTEGER)");
			assertStale(() -> rows(scan));
			assertStale(insert::executeUpdate);
			conn.rollback();
			assertEquals(List.of("[1, one]"), rows(scan));
		}
	}

	@Test
	void testPreparedBatchAcrossCommits() throws SQLException {
		conn.setAutoCommit(false);
		try (PreparedStatement insert = conn.prepareStatement("INSERT INTO t VALUES (?, ?)");
			 PreparedStatement count = prepare("SELECT COUNT(*) FROM t WHERE id > ?", 0)) {
			for (int batch = 0; batch < 3; batch++) {
				for (int row = 0; row < 2; row++) {
					insert.setLong(1, 2 + batch * 2 + row);
					insert.setString(2, "batch");
					insert.addBatch();
				}
				assertArrayEquals(new int[] {1, 1}, insert.executeBatch());
				conn.commit();
				assertEquals(List.of("[" + (3 + batch * 2) + "]"), rows(count));
			}
			assertEquals(7, db.tables().getLiveTable("T").getRowCount());
		}
	}

	/** A table as the validator saw it, of which binding asks only the row type. */
	private static RelOptTable validatedAs(RelDataType rowType) {
		return (RelOptTable) Proxy.newProxyInstance(PlanBindingTest.class.getClassLoader(),
			new Class<?>[] {RelOptTable.class}, (proxy, method, args) -> {
				if (method.getName().equals("getRowType")) return rowType;
				throw new UnsupportedOperationException(method.getName());
			});
	}

	private void execute(String sql) throws SQLException {
		try (Statement stmt = conn.createStatement()) {
			stmt.execute(sql);
		}
	}

	private PreparedStatement prepare(String sql, long param) throws SQLException {
		PreparedStatement stmt = conn.prepareStatement(sql);
		stmt.setLong(1, param);
		return stmt;
	}

	private static List<String> rows(PreparedStatement stmt) throws SQLException {
		List<String> rows = new ArrayList<>();
		try (ResultSet rs = stmt.executeQuery()) {
			int columns = rs.getMetaData().getColumnCount();
			while (rs.next()) {
				List<Object> row = new ArrayList<>();
				for (int i = 1; i <= columns; i++) row.add(rs.getObject(i));
				rows.add(row.toString());
			}
		}
		return rows;
	}

	private static void assertStale(Executable executable) {
		SQLException e = assertThrows(SQLException.class, executable);
		for (Throwable c = e; c != null; c = c.getCause()) {
			if (c instanceof StalePlanException) return;
		}
		fail("expected a StalePlanException cause", e);
	}
}
