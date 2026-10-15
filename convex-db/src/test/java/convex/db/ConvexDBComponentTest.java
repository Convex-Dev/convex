package convex.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.prim.CVMLong;
import convex.core.store.MemoryStore;
import convex.db.lattice.SQLDatabase;
import convex.db.lattice.SQLSchema;
import convex.db.lattice.SQLTable;
import convex.lattice.LatticeHost;

/** Tests Convex DB components over a hosted root: state and forks travel through cursors. */
public class ConvexDBComponentTest {

	private static final String DB_NAME="component-test";
	private static final String TABLE_NAME="items";

	private static LatticeHost<AHashMap<AString,Index<Keyword,ACell>>> host() {
		return LatticeHost.create(ConvexDB.DATABASE_MAP_LATTICE.zero(),new MemoryStore());
	}

	private static SQLDatabase createDatabase(ConvexDB cdb) {
		SQLDatabase db=cdb.database(DB_NAME);
		assertTrue(db.tables().createTable(TABLE_NAME,new String[] {"id","value"}));
		return db;
	}

	@Test
	public void testHostedComponentsShareRootState() {
		LatticeHost<AHashMap<AString,Index<Keyword,ACell>>> root=host();
		ConvexDB cdb=ConvexDB.connect(root);
		SQLDatabase db=createDatabase(cdb);
		SQLTable table=db.tables().getTable(TABLE_NAME);

		assertNotNull(table);
		assertSame(root.cursor().get(),cdb.cursor().get());
		assertEquals(1,root.cursor().get().count());
		assertTrue(ConvexDB.connect(root).database(DB_NAME).tables().tableExists(TABLE_NAME));
	}

	@Test
	public void testDatabaseForkSyncsToOriginal() {
		LatticeHost<AHashMap<AString,Index<Keyword,ACell>>> root=host();
		SQLDatabase original=createDatabase(ConvexDB.connect(root));
		SQLDatabase fork=original.fork();

		assertTrue(fork.tables().insert(TABLE_NAME,1,"forked"));
		assertEquals(0,original.tables().getRowCount(TABLE_NAME));

		fork.sync();
		assertEquals(1,original.tables().getRowCount(TABLE_NAME));
		assertNotNull(original.tables().selectByKey(TABLE_NAME,CVMLong.ONE));
	}

	@Test
	public void testSchemaForkSyncsToOriginal() {
		LatticeHost<AHashMap<AString,Index<Keyword,ACell>>> root=host();
		SQLSchema original=ConvexDB.connect(root).database(DB_NAME).tables();
		SQLSchema fork=original.fork();

		assertTrue(fork.createTable(TABLE_NAME,new String[] {"id"}));
		assertNotNull(fork.getTable(TABLE_NAME));
		assertFalse(original.tableExists(TABLE_NAME));

		fork.sync();
		assertTrue(original.tableExists(TABLE_NAME));
	}
}
