package convex.db.calcite.convention;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;

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
