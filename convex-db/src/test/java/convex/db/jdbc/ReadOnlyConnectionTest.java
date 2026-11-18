package convex.db.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.calcite.avatica.AvaticaConnection;
import org.apache.calcite.avatica.AvaticaPreparedStatement;
import org.apache.calcite.jdbc.CalciteConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import convex.core.crypto.AKeyPair;
import convex.core.data.prim.CVMLong;
import convex.db.ConvexDB;
import convex.db.calcite.ConvexSchema;
import convex.db.lattice.RowLWW;
import convex.db.lattice.SQLDatabase;
import convex.lattice.cursor.ReadOnlyCursorException;
import convex.lattice.cursor.Root;

class ReadOnlyConnectionTest {
	private ConvexDB database() throws SQLException {
		AtomicLong clock = new AtomicLong();
		var db = ConvexDB.create(RowLWW::merge, () -> CVMLong.create(clock.incrementAndGet()));
		try (var c = db.getConnection("test"); var s = c.createStatement()) {
			s.execute("CREATE TABLE t (id INTEGER, v INTEGER)");
			s.execute("INSERT INTO t VALUES (1, 10)");
		}
		return db;
	}

	@Test void componentRestrictionIsLiveAndSurvivesDescentForkAndRebind() {
		var db = SQLDatabase.create("signed", AKeyPair.generate(), RowLWW::merge);
		db.tables().createTable("t", new String[] {"id"});
		var reader = db.readOnly();
		assertSame(reader, reader.readOnly());
		assertNull(reader.getKeyPair());
		assertTrue(reader.isReadOnly());
		assertSame(db.cursor().get(), reader.cursor().get());
		var snapshot = reader.fork();
		db.tables().insert("t", 1L);
		assertNotNull(reader.tables().selectByKey("t", CVMLong.ONE));
		assertNull(snapshot.tables().selectByKey("t", CVMLong.ONE));
		assertThrows(ReadOnlyCursorException.class, () -> reader.tables().insert("t", 2L));
		assertThrows(ReadOnlyCursorException.class, () -> snapshot.tables().insert("t", 2L));
		assertThrows(ReadOnlyCursorException.class, () -> reader.cursor().set(db.cursor().get()));
		assertThrows(ReadOnlyCursorException.class, snapshot::sync);
		var rebound = reader.rebind(Root.create(db.cursor().get()));
		assertTrue(rebound.isReadOnly());
		assertNull(rebound.getKeyPair());
		assertThrows(ReadOnlyCursorException.class, () -> rebound.tables().insert("t", 2L));
	}

	@Test void restrictedBindingSeesNewWritesButTransactionsKeepSnapshots() throws Exception {
		var db = database();
		try (var reader = db.getReadOnlyConnection("test"); var writer = db.getConnection("test");
				var s = writer.createStatement(); var prepared = reader.prepareStatement("SELECT COUNT(*) FROM t")) {
			assertTrue(reader.isReadOnly());
			assertTrue(reader.getMetaData().isReadOnly());
			assertEquals(1, count(reader));
			s.executeUpdate("INSERT INTO t VALUES (2, 20)");
			try (var r = prepared.executeQuery()) { assertTrue(r.next()); assertEquals(2, r.getLong(1)); }
			reader.setAutoCommit(false);
			s.executeUpdate("INSERT INTO t VALUES (3, 30)");
			assertEquals(2, count(reader));
			reader.commit();
			assertEquals(3, count(reader));
			s.executeUpdate("INSERT INTO t VALUES (4, 40)");
			reader.rollback();
			assertEquals(4, count(reader));
			reader.setAutoCommit(true);
			reader.commit(); // no publication of the read-only view
			state("25006", () -> reader.setReadOnly(false));
			assertEquals(4, count(writer));
		}
	}

	@Test void sqlWritesAreRefusedBeforePreparationOrEvaluation() throws Exception {
		var db = database();
		try (var c = db.getReadOnlyConnection("test"); var s = c.createStatement()) {
			for (String sql : new String[] {
					"INSERT INTO t VALUES (2, 20)", "UPDATE t SET v = 1 / 0", "DELETE FROM t",
					"DELETE FROM t WHERE id = -1", "CREATE TABLE x (id INTEGER)", "DROP TABLE t",
					"CREATE TABLE IF NOT EXISTS t (id INTEGER)", "DROP TABLE IF EXISTS missing"}) {
				state("25006", () -> s.execute(sql));
				state("25006", () -> c.prepareStatement(sql));
			}
			state("25006", () -> s.executeLargeUpdate("DELETE FROM t"));
			state("25006", () -> s.addBatch("DELETE FROM t"));
			assertEquals(1, count(c));
			try (var r = s.executeQuery("/* not a write */ WITH x AS (SELECT * FROM t) SELECT COUNT(*) FROM x")) {
				assertTrue(r.next()); assertEquals(1, r.getLong(1));
			}
		}
		assertEquals(1, db.database("test").tables().getTableNames().length);
	}

	@Test void openingAndTransactingOnAnAbsentDatabaseCreatesNothing() throws Exception {
		var db = ConvexDB.create(RowLWW::merge);
		var before = db.cursor().get();
		try (var c = db.getReadOnlyConnection("absent")) {
			c.setAutoCommit(false);
			c.commit();
			c.rollback();
			c.setAutoCommit(true);
			state("25006", () -> c.prepareStatement("CREATE TABLE t (id INTEGER)"));
		}
		assertSame(before, db.cursor().get());
	}

	@Test void ordinaryModeIsReversibleAndPreparedWritesCheckAtExecution() throws Exception {
		var db = database();
		try (var c = db.getConnection("test"); var p = c.prepareStatement("INSERT INTO t VALUES (?, ?)");
				var s = c.createStatement()) {
			p.setInt(1, 2); p.setInt(2, 20);
			p.addBatch();
			s.addBatch("DELETE FROM t");
			c.setReadOnly(true);
			assertTrue(c.isReadOnly());
			state("25006", p::executeUpdate);
			var batch = state("25006", p::executeBatch);
			assertInstanceOf(BatchUpdateException.class, batch);
			assertEquals(0, ((BatchUpdateException) batch).getUpdateCounts().length);
			state("25006", s::executeLargeBatch);
			p.clearBatch(); s.clearBatch();
			c.setAutoCommit(false);
			state("25001", () -> c.setReadOnly(false));
			c.commit();
			assertEquals(1, count(c));
			c.setAutoCommit(true);
			c.setReadOnly(false);
			assertFalse(c.isReadOnly());
			assertEquals(1, p.executeUpdate());
			assertEquals(2, count(c));
		}
	}

	@Test void executeQueryRefusesMutationsBeforeTheyHappen() throws Exception {
		var db = database();
		try (var c = db.getConnection("test"); var s = c.createStatement();
				var p = c.prepareStatement("DELETE FROM t")) {
			for (String sql : new String[] {"INSERT INTO t VALUES (2, 20)", "DROP TABLE t", "CREATE TABLE x (id INTEGER)"}) {
				state("07000", () -> s.executeQuery(sql));
			}
			state("07000", p::executeQuery);
			assertEquals(1, count(c));
			assertEquals(1, db.database("test").tables().getTableNames().length);
		}
	}

	@Test void jdbcViewsDoNotExposeAnUnrestrictedConnectionOrStatement() throws Exception {
		var db = database();
		try (var c = ConvexDriver.connect(db.database("test").readOnly());
				var p = c.prepareStatement("SELECT * FROM t WHERE id = ?")) {
			var calcite = c.unwrap(CalciteConnection.class);
			assertSame(c, calcite);
			state("25006", () -> calcite.setReadOnly(false));
			assertFalse(c.isWrapperFor(AvaticaConnection.class));
			state("0A000", () -> c.unwrap(AvaticaConnection.class));
			state("0A000", () -> p.unwrap(AvaticaPreparedStatement.class));
			state("0A000", () -> p.getParameterMetaData().unwrap(AvaticaPreparedStatement.class));
			assertSame(c, p.getConnection());
			assertSame(c, c.getMetaData().getConnection());
			var exposed = calcite.getRootSchema().getSubSchema("test").unwrap(ConvexSchema.class).getDatabase();
			assertTrue(exposed.isReadOnly());
			assertThrows(ReadOnlyCursorException.class, () -> exposed.tables().deleteByKey("T", CVMLong.ONE));
			p.setInt(1, 1);
			try (var r = p.executeQuery()) {
				assertTrue(r.next());
				assertSame(p, r.getStatement());
				state("25006", () -> r.getStatement().getConnection().setReadOnly(false));
			}
		}
	}

	private static long count(Connection c) throws SQLException {
		try (var s = c.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM t")) {
			assertTrue(r.next()); return r.getLong(1);
		}
	}

	private static SQLException state(String expected, Executable action) {
		var error = assertThrows(SQLException.class, action);
		assertEquals(expected, error.getSQLState(), error.toString());
		return error;
	}
}
