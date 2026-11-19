package convex.db;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.store.MemoryStore;
import convex.db.lattice.RowLWW;
import convex.db.lattice.SQLDatabase;
import convex.db.lattice.SQLTable;
import convex.lattice.LatticeHost;
import convex.lattice.MergeConflictException;

/** Tests Convex DB components over a hosted root: state and forks travel through cursors. */
public class ConvexDBComponentTest {

	private static final String DB_NAME="component-test";
	private static final String TABLE_NAME="items";

	/** Hosts this test created, closed after it */
	private final List<LatticeHost<?>> hosts=new ArrayList<>();

	@AfterEach
	public void closeHosts() {
		List<Executable> cleanup=new ArrayList<>();
		for (LatticeHost<?> host:hosts) cleanup.add(host::close);
		assertAll("Close this test's hosts",cleanup);
	}

	private LatticeHost<AHashMap<AString,Index<Keyword,ACell>>> host() {
		LatticeHost<AHashMap<AString,Index<Keyword,ACell>>> host=LatticeHost.create(Maps.empty(),new MemoryStore());
		hosts.add(host);
		return host;
	}

	/** Edits fields of the database's state that it does not manage, as another writer of the host root would. */
	private static void editDatabase(LatticeHost<AHashMap<AString,Index<Keyword,ACell>>> host,
			UnaryOperator<Index<Keyword,ACell>> edit) {
		AString name=Strings.create(DB_NAME);
		host.cursor().update(map -> map.assoc(name,edit.apply(map.get(name))));
	}

	private static SQLDatabase createDatabase(ConvexDB cdb) {
		SQLDatabase db=cdb.database(DB_NAME);
		assertTrue(db.tables().createTable(TABLE_NAME,new String[] {"id","value"}));
		return db;
	}

	@Test
	public void testHostedComponentsShareRootState() {
		LatticeHost<AHashMap<AString,Index<Keyword,ACell>>> root=host();
		ConvexDB cdb=ConvexDB.connect(root.cursor(),RowLWW::merge);
		SQLDatabase db=createDatabase(cdb);
		SQLTable table=db.tables().getTable(TABLE_NAME);

		assertNotNull(table);
		assertSame(root.cursor().get(),cdb.value());
		assertEquals(1,root.cursor().get().count());
		assertTrue(ConvexDB.connect(root.cursor(),RowLWW::merge).database(DB_NAME).tables().tableExists(TABLE_NAME));
	}

	@Test
	public void testDatabaseForkSyncsToOriginal() {
		LatticeHost<AHashMap<AString,Index<Keyword,ACell>>> root=host();
		SQLDatabase original=createDatabase(ConvexDB.connect(root.cursor(),RowLWW::merge));
		SQLDatabase fork=original.fork();

		assertTrue(fork.tables().insert(TABLE_NAME,1,"forked"));
		assertEquals(0,original.tables().getRowCount(TABLE_NAME));

		fork.sync();
		assertEquals(1,original.tables().getRowCount(TABLE_NAME));
		assertNotNull(original.tables().selectByKey(TABLE_NAME,CVMLong.ONE));
	}

	/** Publishing table edits preserves unrelated current fields, including their deletion. */
	@Test
	public void testDatabasePublicationPreservesConcurrentChanges() {
		var host=host();
		SQLDatabase original=createDatabase(ConvexDB.connect(host.cursor(),RowLWW::merge));
		Keyword retained=Keyword.intern("external");
		Keyword removed=Keyword.intern("removed");
		editDatabase(host,db -> db.assoc(retained,CVMLong.ONE).assoc(removed,CVMLong.ONE));
		SQLDatabase fork=original.fork();
		assertTrue(fork.tables().createTable("draft",new String[] {"id"}));
		assertTrue(original.tables().createTable("concurrent",new String[] {"id"}));
		editDatabase(host,db -> db.assoc(retained,CVMLong.TWO).dissoc(removed));

		fork.sync();
		assertTrue(original.tables().tableExists("draft"));
		assertTrue(original.tables().tableExists("concurrent"));
		assertTrue(original.tables().tableExists(TABLE_NAME));
		assertEquals(CVMLong.TWO,original.value().get(retained));
		assertFalse(original.value().containsKey(removed));
		var published=original.value();
		fork.sync();
		assertSame(published,original.value());
	}

	/** A fork of the whole collection publishes alone, but refuses to reconcile a concurrent change. */
	@Test
	public void testCollectionForkRefusesConcurrentChanges() {
		ConvexDB cdb=ConvexDB.connect(host().cursor(),RowLWW::merge);
		SQLDatabase db=createDatabase(cdb);
		ConvexDB fork=cdb.fork();
		assertTrue(fork.database(DB_NAME).tables().insert(TABLE_NAME,1,"forked"));
		assertTrue(db.tables().insert(TABLE_NAME,2,"concurrent"));
		var current=cdb.value();

		assertThrows(MergeConflictException.class,fork::sync);
		assertSame(current,cdb.value());

		ConvexDB alone=cdb.fork();
		assertTrue(alone.database(DB_NAME).tables().insert(TABLE_NAME,3,"alone"));
		alone.sync();
		assertNotNull(db.tables().selectByKey(TABLE_NAME,CVMLong.create(3)));
	}

	/** A database is the unit of forking: its table store and tables are live views. */
	@Test
	public void testTablesAreNotForkedSeparately() {
		SQLDatabase db=createDatabase(ConvexDB.connect(host().cursor(),RowLWW::merge));
		assertThrows(UnsupportedOperationException.class,() -> db.tables().fork());
		assertThrows(UnsupportedOperationException.class,() -> db.tables().getTable(TABLE_NAME).fork());
	}
}
