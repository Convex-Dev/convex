package convex.db.calcite.convention;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.db.calcite.ConvexColumnType;
import convex.db.calcite.ConvexTableEnumerator;
import convex.db.calcite.ConvexType;
import convex.db.lattice.SQLDatabase;

/**
 * Tests for ConvexConvention infrastructure.
 */
public class ConvexConventionTest {

	@Test
	void testConventionBasics() {
		assertEquals("CONVEX", ConvexConvention.INSTANCE.getName());
		assertEquals(ConvexRel.class, ConvexConvention.INSTANCE.getInterface());
		assertTrue(ConvexConvention.INSTANCE.satisfies(ConvexConvention.INSTANCE));
	}

	@Test
	void testEmptyEnumerable() {
		ConvexEnumerable empty = ConvexEnumerable.empty();
		Iterator<ACell[]> iter = empty.iterator();
		assertFalse(iter.hasNext());
	}

	@Test
	void testEnumerableOf() {
		List<ACell[]> rows = new ArrayList<>();
		rows.add(new ACell[]{CVMLong.create(1), Strings.create("Alice")});
		rows.add(new ACell[]{CVMLong.create(2), Strings.create("Bob")});

		ConvexEnumerable enumerable = ConvexEnumerable.of(rows);

		int count = 0;
		for (ACell[] row : enumerable) {
			assertNotNull(row);
			assertEquals(2, row.length);
			count++;
		}
		assertEquals(2, count);
	}

	@Test
	void testTableEnumeratorScan() {
		SQLDatabase db = SQLDatabase.create("enum_test", convex.core.crypto.AKeyPair.generate());
		db.tables().createTable("t", new String[]{"id", "name"},
			new ConvexColumnType[]{ConvexColumnType.of(ConvexType.INTEGER), ConvexColumnType.varchar(50)});
		db.tables().insert("t", 1L, "Alice");
		db.tables().insert("t", 2L, "Bob");
		db.tables().insert("t", 3L, "Carol");

		ConvexTableEnumerator e = new ConvexTableEnumerator(db.tables(), "t");

		// Before moveNext, current() should throw
		assertThrows(NoSuchElementException.class, e::current);

		// Iterate all rows
		assertTrue(e.moveNext());
		assertNotNull(e.current());
		assertTrue(e.moveNext());
		assertTrue(e.moveNext());
		assertFalse(e.moveNext());

		// After exhaustion, current() should throw
		assertThrows(NoSuchElementException.class, e::current);
	}

	@Test
	void testTableEnumeratorReset() {
		SQLDatabase db = SQLDatabase.create("enum_reset", convex.core.crypto.AKeyPair.generate());
		db.tables().createTable("t", new String[]{"id"},
			new ConvexColumnType[]{ConvexColumnType.of(ConvexType.INTEGER)});
		db.tables().insert("t", 1L);
		db.tables().insert("t", 2L);

		ConvexTableEnumerator e = new ConvexTableEnumerator(db.tables(), "t");

		// First pass
		assertTrue(e.moveNext());
		assertTrue(e.moveNext());
		assertFalse(e.moveNext());

		// Reset and iterate again
		e.reset();
		assertThrows(NoSuchElementException.class, e::current);
		assertTrue(e.moveNext());
		assertNotNull(e.current());
		assertTrue(e.moveNext());
		assertFalse(e.moveNext());
	}

	@Test
	void testTableEnumeratorEmpty() {
		SQLDatabase db = SQLDatabase.create("enum_empty", convex.core.crypto.AKeyPair.generate());
		db.tables().createTable("t", new String[]{"id"},
			new ConvexColumnType[]{ConvexColumnType.of(ConvexType.INTEGER)});

		ConvexTableEnumerator e = new ConvexTableEnumerator(db.tables(), "t");
		assertFalse(e.moveNext());
		assertThrows(NoSuchElementException.class, e::current);
	}

	@Test
	void testEnumerablePreservesCvmTypes() {
		// Verify that ACell types are preserved through enumeration
		CVMLong id = CVMLong.create(42);
		convex.core.data.AString name = Strings.create("Test");

		List<ACell[]> rows = new ArrayList<>();
		rows.add(new ACell[]{id, name});
		ConvexEnumerable enumerable = ConvexEnumerable.of(rows);

		ACell[] row = enumerable.iterator().next();

		// Same instances should be returned (no conversion)
		assertSame(id, row[0]);
		assertSame(name, row[1]);
	}
}
