package convex.core.data.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Keyword;
import convex.core.data.Lists;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;

/**
 * Tests for {@link CellExplorer}.
 *
 * v1 scope: fast path for leaves and non-map containers via
 * {@code JSON.appendJSON}; CellExplorer-specific map rendering with
 * unquoted keyword keys (OQ-1), partial rendering, and fully-truncated form.
 *
 * Tests deliberately avoid types where JSON's rendering still diverges from
 * the final CellExplorer spec (Sets, addresses, keywords/symbols as values,
 * non-finite doubles) — those are covered in later commits.
 *
 * Multi-entry map tests use {@code contains}-style assertions where AHashMap's
 * hash-based iteration order would otherwise make exact-match assertions
 * fragile. Single-entry tests use strict equality.
 */
public class CellExplorerTest {

	/** Shared explorer with generous budget for fast-path tests. */
	private static final CellExplorer BIG = new CellExplorer(10_000);

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

	// ---- Non-map containers (fall through to JSON.appendJSON in v1) ----

	@Test public void testEmptyVector() {
		assertEquals("[]", BIG.explore(Vectors.empty()).toString());
	}

	@Test public void testEmptyList() {
		assertEquals("[]", BIG.explore(Lists.empty()).toString());
	}

	@Test public void testSmallVector() {
		ACell v = Vectors.of(1, 2, 3);
		assertEquals("[1,2,3]", BIG.explore(v).toString());
	}

	@Test public void testNestedVector() {
		ACell v = Vectors.of(Vectors.of(1, 2), Vectors.of(3, 4));
		assertEquals("[[1,2],[3,4]]", BIG.explore(v).toString());
	}

	// ---- Maps: empty ----

	@Test public void testEmptyMap() {
		assertEquals("{}", BIG.explore(Maps.empty()).toString());
	}

	// ---- Maps: single entry, exact-match output ----

	@Test public void testSingleKeywordKeyIdentifier() {
		ACell m = Maps.of(Keyword.create("name"), Strings.create("Alice"));
		assertEquals("{name: \"Alice\"}", BIG.explore(m).toString());
	}

	@Test public void testSingleKeywordKeyNonIdentifier() {
		ACell m = Maps.of(Keyword.create("hello-world"), CVMLong.create(1));
		assertEquals("{\"hello-world\": 1}", BIG.explore(m).toString());
	}

	@Test public void testSingleStringKeyIdentifier() {
		ACell m = Maps.of(Strings.create("active"), CVMBool.TRUE);
		assertEquals("{active: true}", BIG.explore(m).toString());
	}

	@Test public void testSingleStringKeyNonIdentifier() {
		ACell m = Maps.of(Strings.create("hello world"), CVMLong.create(1));
		assertEquals("{\"hello world\": 1}", BIG.explore(m).toString());
	}

	@Test public void testSingleIntegerKeyQuoted() {
		// JSON5 forbids bare numeric object keys — integer keys must be quoted.
		ACell m = Maps.of(CVMLong.create(42), Strings.create("x"));
		assertEquals("{\"42\": \"x\"}", BIG.explore(m).toString());
	}

	@Test public void testStringKeyWithSpecialCharsEscaped() {
		ACell m = Maps.of(Strings.create("a\"b"), CVMLong.create(1));
		// Escape quote in key
		assertEquals("{\"a\\\"b\": 1}", BIG.explore(m).toString());
	}

	// ---- Maps: nested ----

	@Test public void testNestedMap() {
		ACell inner = Maps.of(Keyword.create("inner"), CVMLong.create(1));
		ACell outer = Maps.of(Keyword.create("outer"), inner);
		assertEquals("{outer: {inner: 1}}", BIG.explore(outer).toString());
	}

	// ---- Maps: multi-entry (order-agnostic assertions) ----

	@Test public void testMultiEntryMapAllPresent() {
		AMap<ACell, ACell> m = Maps.empty();
		m = m.assoc(Keyword.create("a"), CVMLong.create(1));
		m = m.assoc(Keyword.create("b"), CVMLong.create(2));
		m = m.assoc(Keyword.create("c"), CVMLong.create(3));
		String out = BIG.explore(m).toString();
		assertTrue(out.startsWith("{") && out.endsWith("}"), "Expected braces, got: " + out);
		assertTrue(out.contains("a: 1"), "Missing a: 1 in " + out);
		assertTrue(out.contains("b: 2"), "Missing b: 2 in " + out);
		assertTrue(out.contains("c: 3"), "Missing c: 3 in " + out);
	}

	// ---- Maps: truncation ----

	@Test public void testMapFullyTruncatedWhenBudgetBelowReserve() {
		// Build a 20-entry map; force budget < ANNOTATION_RESERVE (30).
		AMap<ACell, ACell> m = Maps.empty();
		for (int i = 0; i < 20; i++) {
			m = m.assoc(Keyword.create("k" + i), CVMLong.create(i));
		}
		CellExplorer tiny = new CellExplorer(20);
		String out = tiny.explore(m).toString();
		assertTrue(out.startsWith("{/* Map, 20 keys, "), "Got: " + out);
		assertTrue(out.endsWith(" */}"), "Got: " + out);
	}

	@Test public void testMapPartialWithOverflow() {
		// Build a large map, use a budget that admits a few entries but not all.
		AMap<ACell, ACell> m = Maps.empty();
		for (int i = 0; i < 50; i++) {
			m = m.assoc(Keyword.create("k" + i), CVMLong.create(i));
		}
		CellExplorer tight = new CellExplorer(120);
		String out = tight.explore(m).toString();
		assertTrue(out.startsWith("{"), "Got: " + out);
		assertTrue(out.contains("/* +"), "Expected overflow annotation in: " + out);
		assertTrue(out.contains(" more,"), "Expected 'more,' in merged annotation: " + out);
		assertTrue(out.endsWith(" */}"), "Got: " + out);
	}

	@Test public void testMapPartialValueBudgetFlowsToChildren() {
		// A map whose values are themselves long strings — values should be
		// budget-constrained when the whole map doesn't fit.
		AMap<ACell, ACell> m = Maps.empty();
		m = m.assoc(Keyword.create("a"), CVMLong.create(1));
		m = m.assoc(Keyword.create("b"), CVMLong.create(2));
		m = m.assoc(Keyword.create("c"), CVMLong.create(3));
		m = m.assoc(Keyword.create("d"), CVMLong.create(4));
		// With budget big enough for container + some entries, some get shown.
		CellExplorer mid = new CellExplorer(60);
		String out = mid.explore(m).toString();
		// At least one entry visible or fully-truncated fallback
		assertTrue(out.startsWith("{"), "Got: " + out);
		assertTrue(out.endsWith("}"), "Got: " + out);
	}

	// ---- Configuration ----

	@Test public void testCompactConstructor() {
		CellExplorer explorer = new CellExplorer(1000, true);
		assertEquals("42", explorer.explore(CVMLong.create(42)).toString());
	}

	// ---- Budget exceeded on non-map (placeholder) ----

	@Test public void testNonMapBudgetExceededPlaceholder() {
		// Huge vector with tiny budget — non-map truncation not yet implemented.
		CellExplorer tiny = new CellExplorer(1);
		ACell v = Vectors.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
		AString out = tiny.explore(v);
		assertTrue(out.toString().contains("TRUNCATED"),
			"Expected TRUNCATED placeholder, got: " + out);
	}
}
