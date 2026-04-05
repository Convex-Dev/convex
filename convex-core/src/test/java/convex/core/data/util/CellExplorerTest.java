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

	// ---- Primitives that fit ----

	@Test public void testNil() {
		assertEquals("null", CellExplorer.explore(null, 100).toString());
	}

	@Test public void testBool() {
		assertEquals("true", CellExplorer.explore(CVMBool.TRUE, 100).toString());
		assertEquals("false", CellExplorer.explore(CVMBool.FALSE, 100).toString());
	}

	@Test public void testLong() {
		assertEquals("42", CellExplorer.explore(CVMLong.create(42), 100).toString());
		assertEquals("0", CellExplorer.explore(CVMLong.create(0), 100).toString());
		assertEquals("-1", CellExplorer.explore(CVMLong.create(-1), 100).toString());
	}

	@Test public void testString() {
		assertEquals("\"hello\"", CellExplorer.explore(Strings.create("hello"), 100).toString());
		assertEquals("\"\"", CellExplorer.explore(Strings.create(""), 100).toString());
	}

	// ---- Empty containers ----

	@Test public void testEmptyMap() {
		assertEquals("{}", CellExplorer.explore(Maps.empty(), 100).toString());
	}

	@Test public void testEmptyVector() {
		assertEquals("[]", CellExplorer.explore(Vectors.empty(), 100).toString());
	}

	@Test public void testEmptyList() {
		assertEquals("[]", CellExplorer.explore(Lists.empty(), 100).toString());
	}

	// ---- Small containers that fit in budget ----

	@Test public void testSmallVector() {
		ACell v = Vectors.of(1, 2, 3);
		assertEquals("[1,2,3]", CellExplorer.explore(v, 100).toString());
	}

	@Test public void testNestedVector() {
		ACell v = Vectors.of(Vectors.of(1, 2), Vectors.of(3, 4));
		assertEquals("[[1,2],[3,4]]", CellExplorer.explore(v, 100).toString());
	}

	@Test public void testStringKeyedMap() {
		// String keys avoid the keyword-vs-unquoted-identifier divergence.
		ACell m = Maps.of(Strings.create("a"), CVMLong.create(1));
		assertEquals("{\"a\":1}", CellExplorer.explore(m, 100).toString());
	}

	// ---- Truncation placeholder (skeleton only) ----

	@Test public void testBudgetExceededPlaceholder() {
		// A vector that exceeds a tiny budget should hit the truncation
		// placeholder. Actual truncated rendering comes in later commits.
		ACell v = Vectors.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
		AString out = CellExplorer.explore(v, 1);
		assertTrue(out.toString().contains("TRUNCATED"),
			"Expected TRUNCATED placeholder, got: " + out);
	}

	// ---- Path drill-down ----

	@Test public void testPathEmptyReturnsRoot() {
		ACell v = Vectors.of(10, 20, 30);
		assertEquals("[10,20,30]", CellExplorer.explore(v, "", 100).toString());
		assertEquals("[10,20,30]", CellExplorer.explore(v, "/", 100).toString());
	}

	@Test public void testPathSequenceIndex() {
		ACell v = Vectors.of(10, 20, 30);
		assertEquals("10", CellExplorer.explore(v, "/0", 100).toString());
		assertEquals("20", CellExplorer.explore(v, "/1", 100).toString());
		assertEquals("30", CellExplorer.explore(v, "/2", 100).toString());
	}

	@Test public void testPathStringKey() {
		ACell m = Maps.of(Strings.create("value"), CVMLong.create(42));
		assertEquals("42", CellExplorer.explore(m, "/value", 100).toString());
	}

	@Test public void testPathNested() {
		ACell inner = Vectors.of(10, 20, 30);
		ACell m = Maps.of(Strings.create("items"), inner);
		assertEquals("[10,20,30]", CellExplorer.explore(m, "/items", 100).toString());
		assertEquals("20", CellExplorer.explore(m, "/items/1", 100).toString());
	}

	@Test public void testPathNotFoundMissingKey() {
		ACell m = Maps.of(Strings.create("a"), CVMLong.create(1));
		String out = CellExplorer.explore(m, "/missing", 100).toString();
		assertTrue(out.startsWith("/* path not found:"), "Got: " + out);
		assertTrue(out.contains("/missing"), "Got: " + out);
	}

	@Test public void testPathNotFoundIndexOutOfBounds() {
		ACell v = Vectors.of(1, 2, 3);
		String out = CellExplorer.explore(v, "/99", 100).toString();
		assertTrue(out.startsWith("/* path not found:"), "Got: " + out);
	}

	@Test public void testPathNotFoundNumericOnMap() {
		// A non-numeric map key lookup via path is fine; here we probe that a
		// numeric segment on a map tries keyword/string lookup rather than
		// index semantics. "1" has no matching key → path not found.
		ACell m = Maps.of(Strings.create("a"), CVMLong.create(1));
		String out = CellExplorer.explore(m, "/1", 100).toString();
		assertTrue(out.startsWith("/* path not found:"), "Got: " + out);
	}

	@Test public void testPathIntoLeafFails() {
		// Navigating into a primitive should fail cleanly.
		String out = CellExplorer.explore(CVMLong.create(42), "/foo", 100).toString();
		assertTrue(out.startsWith("/* path not found:"), "Got: " + out);
	}
}
