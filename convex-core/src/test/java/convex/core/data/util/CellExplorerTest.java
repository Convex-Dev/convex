package convex.core.data.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Lists;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;

/**
 * Tests for {@link CellExplorer} v1 skeleton.
 *
 * Coverage is limited to cases where the fast-path delegation to
 * {@code JSON.appendJSON} produces output that matches the CellExplorer
 * specification: nil, bools, longs, strings, and JSON-compatible containers
 * with string keys. Sets, addresses, keywords-as-values, keyword-keyed maps,
 * and non-finite doubles are covered in later commits once CellExplorer
 * provides its own renderings for those types.
 */
public class CellExplorerTest {

	/** Shared explorer with generous budget for fast-path tests. */
	private static final CellExplorer BIG = new CellExplorer(100);

	// ---- Primitives that fit ----

	@Test public void testNil() {
		assertEquals("null", BIG.explore(null).toString());
	}

	@Test public void testBool() {
		assertEquals("true", BIG.explore(CVMBool.TRUE).toString());
		assertEquals("false", BIG.explore(CVMBool.FALSE).toString());
	}

	@Test public void testLong() {
		assertEquals("42", BIG.explore(CVMLong.create(42)).toString());
		assertEquals("0", BIG.explore(CVMLong.create(0)).toString());
		assertEquals("-1", BIG.explore(CVMLong.create(-1)).toString());
	}

	@Test public void testString() {
		assertEquals("\"hello\"", BIG.explore(Strings.create("hello")).toString());
		assertEquals("\"\"", BIG.explore(Strings.create("")).toString());
	}

	// ---- Empty containers ----

	@Test public void testEmptyMap() {
		assertEquals("{}", BIG.explore(Maps.empty()).toString());
	}

	@Test public void testEmptyVector() {
		assertEquals("[]", BIG.explore(Vectors.empty()).toString());
	}

	@Test public void testEmptyList() {
		assertEquals("[]", BIG.explore(Lists.empty()).toString());
	}

	// ---- Small containers that fit in budget ----

	@Test public void testSmallVector() {
		ACell v = Vectors.of(1, 2, 3);
		assertEquals("[1,2,3]", BIG.explore(v).toString());
	}

	@Test public void testNestedVector() {
		ACell v = Vectors.of(Vectors.of(1, 2), Vectors.of(3, 4));
		assertEquals("[[1,2],[3,4]]", BIG.explore(v).toString());
	}

	@Test public void testStringKeyedMap() {
		// String keys avoid the keyword-vs-unquoted-identifier divergence.
		ACell m = Maps.of(Strings.create("a"), CVMLong.create(1));
		assertEquals("{\"a\":1}", BIG.explore(m).toString());
	}

	// ---- Constructor + configuration ----

	@Test public void testCompactConstructor() {
		CellExplorer explorer = new CellExplorer(100, true);
		assertEquals("42", explorer.explore(CVMLong.create(42)).toString());
	}

	// ---- Truncation placeholder (skeleton only) ----

	@Test public void testBudgetExceededPlaceholder() {
		// A vector that exceeds a tiny budget should hit the truncation
		// placeholder. Actual truncated rendering comes in later commits.
		CellExplorer tiny = new CellExplorer(1);
		ACell v = Vectors.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
		AString out = tiny.explore(v);
		assertTrue(out.toString().contains("TRUNCATED"),
			"Expected TRUNCATED placeholder, got: " + out);
	}
}
