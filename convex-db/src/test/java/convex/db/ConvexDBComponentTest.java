package convex.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;
import convex.core.store.MemoryStore;
import convex.db.lattice.RowLWW;
import convex.db.lattice.SQLDatabase;
import convex.db.lattice.SQLTable;
import convex.lattice.LatticeHost;

/** Tests Convex DB components over a hosted root: state and forks travel through cursors. */
public class ConvexDBComponentTest {

	private static final String DB_NAME="component-test";
	private static final String TABLE_NAME="items";

	private static LatticeHost<AHashMap<AString,Index<Keyword,ACell>>> host() {
		return LatticeHost.create(Maps.empty(),new MemoryStore());
	}

	private static SQLDatabase createDatabase(ConvexDB cdb) {
		SQLDatabase db=cdb.database(DB_NAME);
		assertTrue(db.tables().createTable(TABLE_NAME,new String[] {"id","value"}));
		return db;
	}

	@Test
	public void testHostedComponentsShareRootState() {
		LatticeHost<AHashMap<AString,Index<Keyword,ACell>>> root=host();
		ConvexDB cdb=ConvexDB.connect(root,RowLWW::merge);
		SQLDatabase db=createDatabase(cdb);
		SQLTable table=db.tables().getTable(TABLE_NAME);

		assertNotNull(table);
		assertSame(root.cursor().get(),cdb.cursor().get());
		assertEquals(1,root.cursor().get().count());
		assertTrue(ConvexDB.connect(root,RowLWW::merge).database(DB_NAME).tables().tableExists(TABLE_NAME));
	}

	@Test
	public void testDatabaseForkSyncsToOriginal() {
		LatticeHost<AHashMap<AString,Index<Keyword,ACell>>> root=host();
		SQLDatabase original=createDatabase(ConvexDB.connect(root,RowLWW::merge));
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
		SQLDatabase original=createDatabase(ConvexDB.connect(host(),RowLWW::merge));
		Keyword retained=Keyword.intern("external");
		Keyword removed=Keyword.intern("removed");
		original.cursor().update(db -> db.assoc(retained,CVMLong.ONE).assoc(removed,CVMLong.ONE));
		SQLDatabase fork=original.fork();
		assertTrue(fork.tables().createTable("draft",new String[] {"id"}));
		assertTrue(original.tables().createTable("concurrent",new String[] {"id"}));
		original.cursor().update(db -> db.assoc(retained,CVMLong.TWO).dissoc(removed));

		fork.sync();
		assertTrue(original.tables().tableExists("draft"));
		assertTrue(original.tables().tableExists("concurrent"));
		assertTrue(original.tables().tableExists(TABLE_NAME));
		assertEquals(CVMLong.TWO,original.cursor().get().get(retained));
		assertFalse(original.cursor().get().containsKey(removed));
		var published=original.cursor().get();
		fork.sync();
		assertSame(published,original.cursor().get());
	}

	/** A database is the unit of forking: its table store and tables are live views. */
	@Test
	public void testTablesAreNotForkedSeparately() {
		SQLDatabase db=createDatabase(ConvexDB.connect(host(),RowLWW::merge));
		assertThrows(UnsupportedOperationException.class,() -> db.tables().fork());
		assertThrows(UnsupportedOperationException.class,() -> db.tables().getTable(TABLE_NAME).fork());
	}
}
