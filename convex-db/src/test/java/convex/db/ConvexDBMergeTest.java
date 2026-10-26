package convex.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;
import convex.db.calcite.ConvexType;
import convex.db.lattice.RowLWW;
import convex.db.lattice.SQLDatabase;
import convex.db.lattice.SQLRow;
import convex.lattice.LatticeHost;
import convex.lattice.cursor.Cursors;

/**
 * Merges use the policy whatever wraps a database supplies: every route by which
 * state arrives reaches that policy, so it is honoured by host delivery, path
 * delivery, forks, transactions and replica merges alike.
 *
 * <p>The fixture's policy is composed from {@link RowLWW}: it keeps the entry it holds
 * for one table, {@code frozen}, as for reference data fixed on first receipt, and
 * merges the others row by row. The {@code plain} table beside it shows the per-row
 * rule. Row keys are distinct, so no outcome depends on clock timestamps.</p>
 */
public class ConvexDBMergeTest {

	private static final String DB = "merge-test";
	private static final AString DB_NAME = Strings.create(DB);
	private static final AString FROZEN = Strings.create("frozen");
	private static final AString PLAIN = Strings.create("plain");

	// ========== Fixture: a composed policy ==========

	/** {@link RowLWW}, except that a held {@code frozen} entry is kept by every merge. */
	@SuppressWarnings("unchecked")
	static Index<Keyword, ACell> keepFrozen(Index<Keyword, ACell> own, Index<Keyword, ACell> other) {
		Index<Keyword, ACell> merged = RowLWW.merge(own, other);
		AVector<ACell> held = frozen(own);
		if (held == null || Utils.equals(held, frozen(merged))) return merged;
		Index<AString, AVector<ACell>> tables = (Index<AString, AVector<ACell>>) merged.get(ConvexDB.KEY_TABLES);
		return merged.assoc(ConvexDB.KEY_TABLES, tables.assoc(FROZEN, held));
	}

	@SuppressWarnings("unchecked")
	private static AVector<ACell> frozen(Index<Keyword, ACell> db) {
		if (db == null || !(db.get(ConvexDB.KEY_TABLES) instanceof Index<?, ?> tables)) return null;
		return ((Index<AString, AVector<ACell>>) tables).get(FROZEN);
	}

	/** A strictly increasing clock, so no outcome depends on the runtime clock. */
	static Supplier<CVMLong> counter() {
		AtomicLong time = new AtomicLong();
		return () -> CVMLong.create(time.incrementAndGet());
	}

	private static LatticeHost<AHashMap<AString, Index<Keyword, ACell>>> host() {
		return LatticeHost.create(Maps.empty(), null);
	}

	/** Registers a ConvexDB with the composed policy, as an application assembles its own host. */
	private static ConvexDB install(LatticeHost<AHashMap<AString, Index<Keyword, ACell>>> host) {
		return host.addApplication(new ConvexDB(host.cursor(), ConvexDBMergeTest::keepFrozen, counter()));
	}

	/** Creates both tables, each holding row 1. */
	private static void populate(SQLDatabase db) {
		for (AString name : new AString[] {FROZEN, PLAIN}) {
			assertTrue(db.tables().createTable(name, new String[] {"id", "v"},
				new ConvexType[] {ConvexType.INTEGER, ConvexType.VARCHAR}));
			assertTrue(db.tables().insert(name, Vectors.of(CVMLong.create(1), Strings.create("one"))));
		}
	}

	private static void insert(SQLDatabase db, long id) {
		for (AString name : new AString[] {FROZEN, PLAIN}) {
			assertTrue(db.tables().insert(name, Vectors.of(CVMLong.create(id), Strings.create("row " + id))));
		}
	}

	private static boolean has(SQLDatabase db, AString table, long id) {
		return db.tables().selectByKey(table, CVMLong.create(id)) != null;
	}

	/** The state of a remote node that started from the same root, then wrote row 2. */
	private static AHashMap<AString, Index<Keyword, ACell>> remoteWithRow2(AHashMap<AString, Index<Keyword, ACell>> root) {
		ConvexDB remote = ConvexDB.connect(Cursors.root(root), RowLWW::merge);
		insert(remote.database(DB), 2);
		return remote.cursor().get();
	}

	/** The key under which an integer primary key is stored. */
	private static ABlob key(long id) {
		return Blob.fromHex(String.format("%016x", id));
	}

	// ========== A supplied policy ==========

	@Test
	public void testHostDeliveryUsesThePolicy() {
		var host = host();
		SQLDatabase db = install(host).database(DB);
		populate(db);

		host.merge(remoteWithRow2(host.cursor().get()));
		assertTrue(has(db, PLAIN, 2));
		assertFalse(has(db, FROZEN, 2));
	}

	@Test
	public void testPathDeliveryUsesThePolicy() {
		var host = host();
		SQLDatabase db = install(host).database(DB);
		populate(db);
		var remote = remoteWithRow2(host.cursor().get());
		var remoteTables = ConvexDB.connect(Cursors.root(remote), RowLWW::merge).database(DB).tables();

		// One row, below the table entry
		AVector<ACell> row = SQLRow.create(Vectors.of(CVMLong.create(3), Strings.create("three")), CVMLong.create(5));
		for (AString name : new AString[] {FROZEN, PLAIN}) {
			host.mergeAt(row, DB_NAME, ConvexDB.KEY_TABLES, name, CVMLong.ONE, key(3));
		}
		assertTrue(has(db, PLAIN, 3));
		assertFalse(has(db, FROZEN, 3));

		// Whole table entries, and the whole table store
		for (AString name : new AString[] {FROZEN, PLAIN}) {
			host.mergeAt(remoteTables.getTable(name).getState(), DB_NAME, ConvexDB.KEY_TABLES, name);
		}
		assertTrue(has(db, PLAIN, 2));
		assertFalse(has(db, FROZEN, 2));
		var before = host.cursor().get();
		host.mergeAt(remoteTables.cursor().get(), DB_NAME, ConvexDB.KEY_TABLES);
		host.mergeAt(remote.get(DB_NAME), DB_NAME);
		assertSame(before, host.cursor().get());
	}

	/** A fork's publication reconciles with concurrent changes through the policy. */
	@Test
	public void testForkSyncUsesThePolicy() {
		SQLDatabase db = install(host()).database(DB);
		populate(db);

		SQLDatabase tx = db.fork();
		insert(tx, 2);
		insert(db, 3);
		tx.sync();
		assertTrue(has(db, PLAIN, 2) && has(db, PLAIN, 3));
		assertTrue(has(db, FROZEN, 2), "the fork's own entry is kept");
		assertFalse(has(db, FROZEN, 3));
	}

	/** A host fork exchanges roots with its origin by delivery, each side applying its own policy. */
	@Test
	public void testHostForkSyncUsesThePolicy() {
		var origin = host();
		SQLDatabase db = install(origin).database(DB);
		populate(db);
		var fork = origin.fork();
		SQLDatabase forked = install(fork).database(DB);

		insert(forked, 2);
		insert(db, 3);
		fork.sync();
		assertTrue(has(db, PLAIN, 2) && has(forked, PLAIN, 3));
		assertFalse(has(db, FROZEN, 2));
		assertFalse(has(forked, FROZEN, 3));
	}

	/** A JDBC transaction is a fork of the database the connection resolved, with its policy. */
	@Test
	public void testTransactionUsesThePolicy() throws Exception {
		ConvexDB cdb = install(host());
		SQLDatabase db = cdb.database(DB);
		populate(db);

		try (Connection conn = cdb.getConnection(DB); Statement stmt = conn.createStatement()) {
			conn.setAutoCommit(false);
			stmt.executeUpdate("INSERT INTO frozen VALUES (2, 'two')");
			stmt.executeUpdate("INSERT INTO plain VALUES (2, 'two')");
			insert(db, 3);
			conn.commit();
		}
		assertTrue(has(db, PLAIN, 2) && has(db, PLAIN, 3));
		assertTrue(has(db, FROZEN, 2));
		assertFalse(has(db, FROZEN, 3));
	}

	@Test
	public void testReplicaMergeUsesThePolicy() {
		AKeyPair local = AKeyPair.generate();
		AKeyPair other = AKeyPair.generate();
		SQLDatabase db = SQLDatabase.create(DB, local, local.getAccountKey(), ConvexDBMergeTest::keepFrozen, counter());
		populate(db);
		SQLDatabase remote = SQLDatabase.create(DB, other, RowLWW::merge);
		remote.cursor().set(db.cursor().get());
		insert(remote, 2);

		assertEquals(1, db.mergeReplicas(remote.exportReplica()));
		assertTrue(has(db, PLAIN, 2));
		assertFalse(has(db, FROZEN, 2));
	}

	// ========== Per-row last-write-wins ==========

	@Test
	public void testOpenHostRegistersConvexDB() throws Exception {
		var host = ConvexDB.openHost(null, RowLWW::merge);
		ConvexDB cdb = ConvexDB.connect(host);
		assertSame(cdb, ConvexDB.connect(host));
		populate(cdb.database(DB));

		host.merge(remoteWithRow2(host.cursor().get()));
		assertTrue(has(cdb.database(DB), FROZEN, 2), "RowLWW merges rows by key");
		assertTrue(has(cdb.database(DB), PLAIN, 2));

		// A host assembled elsewhere has no registered ConvexDB: a view needs its policy
		var plain = host();
		assertThrows(IllegalArgumentException.class, () -> ConvexDB.connect(plain));
		assertNotSame(ConvexDB.connect(plain, RowLWW::merge), ConvexDB.connect(plain, RowLWW::merge));
	}

	@Test
	public void testRowDeliveryIntoLiveDroppedAndAbsentTables() throws Exception {
		var host = ConvexDB.openHost(null, RowLWW::merge);
		SQLDatabase db = ConvexDB.connect(host).database(DB);
		populate(db);
		AVector<ACell> row = SQLRow.create(Vectors.of(CVMLong.create(2), Strings.create("two")), CVMLong.create(5));
		AVector<ACell> created = db.tables().getTable(PLAIN).getState();

		host.mergeAt(row, DB_NAME, ConvexDB.KEY_TABLES, PLAIN, CVMLong.ONE, key(2));
		assertTrue(has(db, PLAIN, 2));
		assertEquals(2, db.tables().getRowCount(PLAIN));
		AVector<ACell> delivered = db.tables().getTable(PLAIN).getState();
		for (int i : new int[] {0, 2}) {
			assertSame(created.get(i), delivered.get(i), "row delivery leaves the schema and its timestamp");
		}

		// A row index at the rows position
		host.mergeAt(Index.of(key(3), row), DB_NAME, ConvexDB.KEY_TABLES, PLAIN, CVMLong.ONE);
		assertEquals(3, db.tables().getRowCount(PLAIN));

		assertTrue(db.tables().dropTable(FROZEN));
		var before = host.cursor().get();
		host.mergeAt(row, DB_NAME, ConvexDB.KEY_TABLES, FROZEN, CVMLong.ONE, key(2));
		host.mergeAt(row, DB_NAME, ConvexDB.KEY_TABLES, Strings.create("absent"), CVMLong.ONE, key(2));
		host.mergeAt(row, Strings.create("absent"), ConvexDB.KEY_TABLES, PLAIN, CVMLong.ONE, key(2));
		assertSame(before, host.cursor().get(), "dropped and absent tables receive nothing");

		// Paths into the entry other than its rows are refused
		assertThrows(UnsupportedOperationException.class,
			() -> host.mergeAt(Vectors.empty(), DB_NAME, ConvexDB.KEY_TABLES, PLAIN, CVMLong.ZERO));
		assertThrows(UnsupportedOperationException.class,
			() -> host.mergeAt(row, DB_NAME, ConvexDB.KEY_TABLES, PLAIN, CVMLong.ONE, key(2), CVMLong.ZERO));
		assertSame(before, host.cursor().get());
	}

	@Test
	public void testMissingDatabaseIsNotCreated() throws Exception {
		var host = ConvexDB.openHost(null, RowLWW::merge);
		ConvexDB cdb = ConvexDB.connect(host);
		var empty = host.cursor().get();

		SQLDatabase db = cdb.database("missing");
		assertEquals(0, db.tables().getTableNames().length);
		assertFalse(db.tables().tableExists("t"));
		assertNull(db.tables().getTable("t"));
		assertTrue(db.tables().selectAll("t").isEmpty());
		assertFalse(db.tables().insert("t", 1, "one"), "no table to write to");
		db.fork().sync();
		try (Connection conn = cdb.getConnection("missing")) {
			conn.getMetaData().getTables(null, null, null, null).close();
		}
		assertEquals(0, SQLDatabase.connect(host.cursor(), "missing", RowLWW::merge).tables().getTableNames().length);

		// Deliveries that carry nothing the policy accepts create nothing either
		host.merge(Maps.of(Strings.create("missing"), Index.of(Keyword.intern("other"), CVMLong.ONE)));
		host.merge(Maps.of(Strings.create("missing"), Index.of(ConvexDB.KEY_TABLES, Index.none())));
		host.mergeAt(CVMLong.ONE, Strings.create("missing"), Keyword.intern("other"));
		assertSame(empty, host.cursor().get());
		assertEquals(0, cdb.getDatabaseNames().length);

		// The first changed write creates it
		assertTrue(db.tables().createTable("t", new String[] {"id"}));
		assertEquals(1, cdb.getDatabaseNames().length);
	}
}
