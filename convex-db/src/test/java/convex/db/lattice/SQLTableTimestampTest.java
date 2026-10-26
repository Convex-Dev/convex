package convex.db.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.Root;

/**
 * The table entry's utime is its schema timestamp (CAD039): row writes leave it
 * unchanged, so under {@link RowLWW} a later row write on a node that has not seen a
 * schema change or a DROP TABLE cannot make the older schema win.
 */
public class SQLTableTimestampTest {

	private static final AVector<AVector<ACell>> SCHEMA = Vectors.of(
		Vectors.of(Strings.create("id"), Strings.create("INTEGER")));
	private static final AVector<AVector<ACell>> ALTERED = Vectors.of(
		Vectors.of(Strings.create("id"), Strings.create("INTEGER")),
		Vectors.of(Strings.create("name"), Strings.create("VARCHAR")));

	private static final CVMLong CREATED = CVMLong.create(1);
	private static final CVMLong CHANGED = CVMLong.create(2);
	private static final CVMLong WRITTEN = CVMLong.create(3);

	private static final ABlob PK = Strings.create("k").toBlob();

	@Test
	public void testRowWritesKeepUtime() {
		SQLTable table = table(SQLTable.createState(SCHEMA, CREATED));

		assertTrue(table.insertRow(PK, Vectors.of(1L), WRITTEN));
		assertEquals(CREATED, table.getTimestamp());
		assertTrue(table.deleteRow(PK, CVMLong.create(4)));
		assertEquals(CREATED, table.getTimestamp());
	}

	/** A node that never saw the drop writes a row later; the drop still wins. */
	@Test
	public void testStaleRowWriteCannotRestoreDroppedTable() {
		SQLTable stale = table(SQLTable.createState(SCHEMA, CREATED));
		stale.insertRow(PK, Vectors.of(1L), WRITTEN);
		AVector<ACell> dropped = SQLTable.createTombstoneState(CHANGED);

		assertFalse(SQLTable.isLiveState(RowLWW.mergeTable(stale.getState(), dropped)));
		assertFalse(SQLTable.isLiveState(RowLWW.mergeTable(dropped, stale.getState())));
	}

	/** A node that never saw the schema change writes a row later; the newer schema still wins. */
	@Test
	public void testStaleRowWriteCannotRevertSchema() {
		SQLTable stale = table(SQLTable.createState(SCHEMA, CREATED));
		stale.insertRow(PK, Vectors.of(1L), WRITTEN);
		AVector<ACell> altered = SQLTable.createState(ALTERED, CHANGED);

		assertEquals(ALTERED, RowLWW.mergeTable(stale.getState(), altered).get(SQLTable.POS_SCHEMA));
		assertEquals(ALTERED, RowLWW.mergeTable(altered, stale.getState()).get(SQLTable.POS_SCHEMA));
	}

	private static SQLTable table(AVector<ACell> state) {
		return SQLSchema.connect(Root.create(Index.of(Strings.create("t"), state))).getTable("t");
	}
}
