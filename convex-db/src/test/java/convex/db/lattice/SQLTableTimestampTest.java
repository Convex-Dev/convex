package convex.db.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Supplier;

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
 * The table entry's ctime is the time the table was created (CAD039). Row writes leave it
 * unchanged, so under {@link RowLWW} a later row write on a node that has not seen a DROP
 * TABLE or a re-creation cannot make the older table win. Rows carry the clock's reading
 * as it is: how a writer timestamps its writes is the application's choice.
 */
public class SQLTableTimestampTest {

	private static final AVector<AVector<ACell>> SCHEMA = Vectors.of(
		Vectors.of(Strings.create("id"), Strings.create("INTEGER")));
	private static final AVector<AVector<ACell>> NEW_SCHEMA = Vectors.of(
		Vectors.of(Strings.create("id"), Strings.create("INTEGER")),
		Vectors.of(Strings.create("name"), Strings.create("VARCHAR")));

	private static final CVMLong BEHIND = CVMLong.ZERO;
	private static final CVMLong CREATED = CVMLong.create(1);
	private static final CVMLong CHANGED = CVMLong.create(2);
	private static final CVMLong WRITTEN = CVMLong.create(3);

	private static final ABlob PK = Strings.create("k").toBlob();

	@Test
	public void testRowWritesKeepCtime() {
		SQLTable table = table(SQLTable.createState(SCHEMA, CREATED));

		assertTrue(table.insertRow(PK, Vectors.of(1L), WRITTEN));
		assertEquals(CREATED, table.getTimestamp());
		assertTrue(table.deleteRow(PK, CVMLong.create(4)));
		assertEquals(CREATED, table.getTimestamp());
	}

	/**
	 * A row is stamped with the clock's reading even when that is before the table's ctime.
	 * Under RowLWW such a row belongs to an earlier table and is dropped on merge (#802).
	 */
	@Test
	public void testRowStampIsTheClockReading() {
		SQLTable table = table(SQLTable.createState(SCHEMA, CREATED), () -> BEHIND);
		AVector<ACell> created = table.getState();

		assertTrue(table.insertRow(PK, Vectors.of(1L)));
		assertEquals(BEHIND, SQLRow.getTimestamp(table.getRows().get(PK)));
		assertEquals(0, SQLTable.getLiveCount(RowLWW.mergeTable(created, table.getState())));
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

	/** A node that never saw the table created again with another schema writes a row later; the newer schema still wins. */
	@Test
	public void testStaleRowWriteCannotRevertSchema() {
		SQLTable stale = table(SQLTable.createState(SCHEMA, CREATED));
		stale.insertRow(PK, Vectors.of(1L), WRITTEN);
		AVector<ACell> recreated = SQLTable.createState(NEW_SCHEMA, CHANGED);

		assertEquals(NEW_SCHEMA, RowLWW.mergeTable(stale.getState(), recreated).get(SQLTable.POS_SCHEMA));
		assertEquals(NEW_SCHEMA, RowLWW.mergeTable(recreated, stale.getState()).get(SQLTable.POS_SCHEMA));
	}

	private static SQLTable table(AVector<ACell> state) {
		return SQLSchema.connect(Root.create(Index.of(Strings.create("t"), state))).getTable("t");
	}

	private static SQLTable table(AVector<ACell> state, Supplier<CVMLong> clock) {
		return new SQLSchema(Root.create(Index.of(Strings.create("t"), state)), clock).getTable("t");
	}
}
