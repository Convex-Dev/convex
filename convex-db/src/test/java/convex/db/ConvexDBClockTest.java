package convex.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.db.calcite.ConvexType;
import convex.db.lattice.RowLWW;
import convex.db.lattice.SQLDatabase;
import convex.db.lattice.SQLRow;
import convex.db.lattice.SQLSchema;

/**
 * The clock is an explicit dependency the application supplies. Factories pass it
 * to every database and table, forks and transactions keep it, each write reads it
 * once, and merges and reads never read it.
 */
public class ConvexDBClockTest {

	private static final String DB = "clock-test";
	private static final String TABLE = "T";

	/** A clock counting its reads, each returning the next integer. */
	static final class Clock implements Supplier<CVMLong> {
		final AtomicLong reads = new AtomicLong();
		@Override public CVMLong get() { return CVMLong.create(reads.incrementAndGet()); }
	}

	private static final Supplier<CVMLong> NO_CLOCK = () -> {
		throw new AssertionError("Merges and reads must not read the clock");
	};

	private static AVector<ACell> row(SQLSchema tables, long id) {
		return tables.getTable(TABLE).getRows().get(Blob.fromHex(String.format("%016x", id)));
	}

	@Test
	public void testEveryWriteReadsTheClockOnce() throws Exception {
		Clock clock = new Clock();
		ConvexDB cdb = ConvexDB.create(RowLWW::merge, clock);
		SQLSchema tables = cdb.database(DB).tables();

		assertTrue(tables.createTable(TABLE, new String[] {"id", "v"},
			new ConvexType[] {ConvexType.INTEGER, ConvexType.VARCHAR}));
		assertEquals(CVMLong.create(1), tables.getTable(TABLE).getTimestamp());
		assertTrue(tables.insert(TABLE, 1, "one"));
		assertEquals(CVMLong.create(2), SQLRow.getTimestamp(row(tables, 1)));
		assertTrue(tables.deleteByKey(TABLE, CVMLong.create(1)));
		assertEquals(CVMLong.create(3), SQLRow.getDeleted(row(tables, 1)));
		assertEquals(3, clock.reads.get());

		// Forks keep the clock
		SQLDatabase tx = cdb.database(DB).fork();
		assertTrue(tx.tables().insert(TABLE, 2, "two"));
		tx.sync();
		assertEquals(CVMLong.create(4), SQLRow.getTimestamp(row(tables, 2)));

		// So does a JDBC transaction, which forks the connection's database
		try (Connection conn = cdb.getConnection(DB); Statement stmt = conn.createStatement()) {
			conn.setAutoCommit(false);
			stmt.executeUpdate("INSERT INTO t VALUES (3, 'three')");
			conn.commit();
		}
		assertEquals(CVMLong.create(5), SQLRow.getTimestamp(row(tables, 3)));

		assertTrue(tables.dropTable(TABLE));
		assertEquals(CVMLong.create(6), tables.getTable(TABLE).getTimestamp());
		assertEquals(6, clock.reads.get());
	}

	@Test
	public void testHostAndStandaloneDatabasesUseTheSuppliedClock() throws Exception {
		Clock clock = new Clock();
		var host = ConvexDB.openHost(null, RowLWW::merge, clock);
		assertSame(clock, ConvexDB.connect(host).getClock());
		assertTrue(ConvexDB.connect(host).database(DB).tables().createTable(TABLE, new String[] {"id"}));
		assertEquals(1, clock.reads.get());

		Clock replicaClock = new Clock();
		AKeyPair kp = AKeyPair.generate();
		SQLDatabase standalone = SQLDatabase.create(DB, kp, kp.getAccountKey(), RowLWW::merge, replicaClock);
		assertTrue(standalone.tables().createTable(TABLE, new String[] {"id"}));
		assertTrue(standalone.tables().insert(TABLE, 1));
		assertEquals(CVMLong.create(2), SQLRow.getTimestamp(row(standalone.tables(), 1)));
	}

	@Test
	public void testMergesAndReadsDoNotReadTheClock() throws Exception {
		ConvexDB source = ConvexDB.create(RowLWW::merge, new Clock());
		SQLSchema sourceTables = source.database(DB).tables();
		sourceTables.createTable(TABLE, new String[] {"id", "v"});
		sourceTables.insert(TABLE, 1, "one");
		var remote = source.cursor().get();

		var host = ConvexDB.openHost(null, RowLWW::merge, NO_CLOCK);
		ConvexDB cdb = ConvexDB.connect(host);
		host.merge(remote);
		host.mergeAt(row(sourceTables, 1), Strings.create(DB), ConvexDB.KEY_TABLES, Strings.create(TABLE),
			CVMLong.ONE, Blob.fromHex(String.format("%016x", 1)));
		SQLSchema tables = cdb.database(DB).tables();
		assertEquals(1, tables.getRowCount(TABLE));
		assertEquals(Vectors.of(CVMLong.create(1), Strings.create("one")), tables.selectByKey(TABLE, CVMLong.create(1)));
		assertEquals(1, tables.getTableNames().length);

		// A fork with no edits syncs without stamping anything
		cdb.database(DB).fork().sync();

		// Replicas merge without stamping too
		AKeyPair local = AKeyPair.generate();
		AKeyPair other = AKeyPair.generate();
		SQLDatabase replica = SQLDatabase.create(DB, other, other.getAccountKey(), RowLWW::merge, new Clock());
		replica.cursor().set(source.database(DB).cursor().get());
		SQLDatabase db = SQLDatabase.create(DB, local, local.getAccountKey(), RowLWW::merge, NO_CLOCK);
		assertEquals(1, db.mergeReplicas(replica.exportReplica()));
		assertEquals(1, db.tables().getRowCount(TABLE));
	}
}
