package convex.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.prim.CVMLong;
import convex.db.lattice.SQLDatabase;
import convex.db.lattice.SQLSchema;
import convex.db.lattice.SQLTable;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.Cursors;

/** Tests Convex DB integration with the lattice component policy hierarchy. */
public class ConvexDBComponentTest {

	private static final String DB_NAME="component-test";
	private static final String TABLE_NAME="items";

	private static class TestRoot extends
			ALatticeComponent<AHashMap<AString,Index<Keyword,ACell>>> {

		private int persistCount;

		TestRoot() {
			super(Cursors.createLattice(ConvexDB.DATABASE_MAP_LATTICE));
		}

		@Override
		protected <T extends ACell> T persist(T value) {
			persistCount++;
			return value;
		}
	}

	private static SQLDatabase createDatabase(ConvexDB cdb) {
		SQLDatabase db=cdb.database(DB_NAME);
		assertTrue(db.tables().createTable(TABLE_NAME,new String[] {"id","value"}));
		return db;
	}

	@Test
	public void testNestedPersistenceDelegatesWithoutMovingCursors() throws Exception {
		TestRoot root=new TestRoot();
		ConvexDB cdb=ConvexDB.connect(root);
		SQLDatabase db=createDatabase(cdb);
		SQLTable table=db.tables().getTable(TABLE_NAME);

		AHashMap<AString,Index<Keyword,ACell>> rootBefore=root.cursor().get();
		AVector<ACell> tableBefore=table.cursor().get();
		AVector<ACell> persisted=table.persist();

		assertEquals(1,root.persistCount);
		assertSame(tableBefore,persisted);
		assertSame(tableBefore,table.cursor().get());
		assertSame(rootBefore,root.cursor().get());
	}

	@Test
	public void testStandaloneComponentsRemainWithoutPersistencePolicy() {
		ConvexDB cdb=ConvexDB.create();
		SQLTable cdbTable=createDatabase(cdb).tables().getTable(TABLE_NAME);
		assertThrows(IllegalStateException.class,cdbTable::persist);
		assertTrue(cdb.database(DB_NAME).tables().tableExists(TABLE_NAME));

		SQLDatabase standalone=SQLDatabase.create(DB_NAME,AKeyPair.generate());
		assertTrue(standalone.tables().createTable(TABLE_NAME,new String[] {"id"}));
		assertThrows(IllegalStateException.class,
			standalone.tables().getTable(TABLE_NAME)::persist);
	}

	@Test
	public void testDatabaseForkRetainsPolicyParentAndSyncsToOriginal() throws Exception {
		TestRoot root=new TestRoot();
		SQLDatabase original=createDatabase(ConvexDB.connect(root));
		SQLDatabase fork=original.fork();

		assertTrue(fork.tables().insert(TABLE_NAME,1,"forked"));
		AHashMap<AString,Index<Keyword,ACell>> rootBefore=root.cursor().get();
		fork.tables().getTable(TABLE_NAME).persist();

		assertEquals(1,root.persistCount);
		assertSame(rootBefore,root.cursor().get());
		assertEquals(0,original.tables().getRowCount(TABLE_NAME));

		fork.sync();
		assertEquals(1,original.tables().getRowCount(TABLE_NAME));
		assertNotNull(original.tables().selectByKey(TABLE_NAME,CVMLong.ONE));
	}

	@Test
	public void testSchemaForkRetainsPolicyParentAndSyncsToOriginal() throws Exception {
		TestRoot root=new TestRoot();
		SQLSchema original=ConvexDB.connect(root).database(DB_NAME).tables();
		SQLSchema fork=original.fork();

		assertTrue(fork.createTable(TABLE_NAME,new String[] {"id"}));
		SQLTable forkTable=fork.getTable(TABLE_NAME);
		AHashMap<AString,Index<Keyword,ACell>> rootBefore=root.cursor().get();
		forkTable.persist();

		assertEquals(1,root.persistCount);
		assertSame(rootBefore,root.cursor().get());
		assertFalse(original.tableExists(TABLE_NAME));

		fork.sync();
		assertTrue(original.tableExists(TABLE_NAME));
	}
}
