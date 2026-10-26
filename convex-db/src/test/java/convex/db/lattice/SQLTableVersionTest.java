package convex.db.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.db.calcite.ConvexType;
import convex.lattice.cursor.Root;

/**
 * The table entry's format version (CAD039). Entries without a version field are
 * version 0 and keep exactly their four fields; a version field is carried through
 * {@link RowLWW} merges and row updates; tables of an unsupported version are refused,
 * not misread.
 */
public class SQLTableVersionTest {

	private static final AVector<AVector<ACell>> SCHEMA = Vectors.of(
		Vectors.of(Strings.create("id"), Strings.create("INTEGER")),
		Vectors.of(Strings.create("name"), Strings.create("VARCHAR")));

	@Test
	public void testVersion0EntriesKeepFourFields() {
		SQLSchema a = SQLSchema.create();
		SQLSchema b = SQLSchema.create();
		for (SQLSchema tables : new SQLSchema[] {a, b}) {
			tables.createTable("t", new String[] {"id", "name"}, new ConvexType[] {ConvexType.INTEGER, ConvexType.VARCHAR});
		}
		a.insert("t", 1L, "one");
		b.insert("t", 2L, "two");
		AVector<ACell> stateA = a.getTable("t").getState();
		assertEquals(4, stateA.count(), "a new table is a version 0 entry");
		assertEquals(0, SQLTable.getVersion(stateA));

		AVector<ACell> merged = RowLWW.mergeTable(stateA, b.getTable("t").getState());
		assertEquals(4, merged.count(), "merging version 0 entries adds no version field");
		assertEquals(2, ((Index<?, ?>) merged.get(SQLTable.POS_ROWS)).count());
	}

	@Test
	public void testMergeTakesSchemaWinnersVersion() {
		AVector<ACell> earlier0 = SQLTable.createState(SCHEMA, CVMLong.create(1));
		AVector<ACell> version1 = withVersion(SQLTable.createState(SCHEMA, CVMLong.create(2)), 1);
		AVector<ACell> later0 = SQLTable.createState(SCHEMA, CVMLong.create(3));

		assertEquals(1, SQLTable.getVersion(RowLWW.mergeTable(earlier0, version1)));
		assertEquals(1, SQLTable.getVersion(RowLWW.mergeTable(version1, earlier0)));

		AVector<ACell> merged = RowLWW.mergeTable(version1, later0);
		assertEquals(0, SQLTable.getVersion(merged));
		assertEquals(4, merged.count(), "a version 0 winner keeps four fields");
	}

	/** An older implementation rebuilds an entry from four fields; a later merge restores the version. */
	@Test
	public void testTieRestoresDroppedVersion() {
		AVector<ACell> versioned = withVersion(SQLTable.createState(SCHEMA, CVMLong.create(5)), 1);
		AVector<ACell> dropped = versioned.slice(0, SQLTable.POS_VERSION);

		AVector<ACell> ab = RowLWW.mergeTable(dropped, versioned);
		AVector<ACell> ba = RowLWW.mergeTable(versioned, dropped);
		assertEquals(1, SQLTable.getVersion(ab));
		assertEquals(ab, ba, "the tie is resolved the same way in either order");
	}

	@Test
	public void testRowUpdatesKeepVersionField() {
		AVector<ACell> state = withVersion(SQLTable.createState(SCHEMA, CVMLong.create(1)), 0);
		SQLSchema tables = schemaWith("t", state);

		tables.insert("t", 1L, "one");
		assertEquals(5, tables.getTable("t").getState().count());
		tables.deleteByKey("t", CVMLong.create(1));
		assertEquals(5, tables.getTable("t").getState().count());
	}

	@Test
	public void testUnsupportedVersionRefused() {
		AVector<ACell> state = withVersion(SQLTable.createState(SCHEMA, CVMLong.create(1)), 1);
		SQLSchema tables = schemaWith("t", state);

		assertThrows(IllegalStateException.class, () -> tables.getLiveTable("t"));
		assertThrows(IllegalStateException.class, () -> tables.selectByKey("t", CVMLong.create(1)));
		assertThrows(IllegalStateException.class, () -> tables.insert("t", 1L, "one"));
		assertThrows(IllegalStateException.class, () -> tables.getTable("t").insertRow(
			Strings.create("k").toBlob(), Vectors.of(1L, "one"), CVMLong.create(2)));
		assertEquals(0, tables.getTableNames().length, "listings leave out tables they cannot read");
		assertEquals(state, tables.getTable("t").getState(), "a refused table is left unchanged");

		// Merge never refuses: it carries the version for implementations that support it
		AVector<ACell> merged = RowLWW.mergeTable(state, SQLTable.createState(SCHEMA, CVMLong.create(0)));
		assertEquals(1, SQLTable.getVersion(merged));
	}

	private static AVector<ACell> withVersion(AVector<ACell> state, long version) {
		return state.append(CVMLong.create(version));
	}

	private static SQLSchema schemaWith(String name, AVector<ACell> state) {
		Index<AString, AVector<ACell>> store = Index.of(Strings.create(name), state);
		return SQLSchema.connect(Root.create(store));
	}
}
