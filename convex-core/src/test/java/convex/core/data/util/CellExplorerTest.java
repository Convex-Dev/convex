package convex.core.data.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.ACollection;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Keyword;
import convex.core.data.Lists;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.util.JSON;

/**
 * Tests for {@link CellExplorer}.
 *
 * These tests assert semantic properties of CellExplorer output rather than
 * exact format strings. The key guarantee is that every rendering is valid
 * JSON5 and re-loads into a CVM cell whose structure matches the input
 * (modulo JSON5's type mapping: keywords become strings, sets become
 * sequences, and truncation annotations are comments that drop during parse).
 *
 * Format-specific checks are reserved for a small number of "feature" tests
 * (OQ-1 unquoted keyword keys, JSON5 integer-key quoting, set marker) where
 * the exact form is what's being tested.
 */
public class CellExplorerTest {

	/** Generous budget — everything fits the fast path. */
	private static final CellExplorer BIG = new CellExplorer(10_000);

	/** Parse CellExplorer output back as JSON5. Asserts parse succeeds. */
	private static ACell parseBack(AString out) {
		return assertDoesNotThrow(() -> JSON.parseJSON5(out.toString()),
			"CellExplorer output failed to parse as JSON5: " + out);
	}

	/** Parse CellExplorer output as JSON5, returning the parsed cell (may be null for nil). */
	private static ACell roundTrip(CellExplorer ex, ACell input) {
		return parseBack(ex.explore(input));
	}

	// =================================================================
	// Leaves: strict output form
	// =================================================================

	@Test public void testNil() {
		assertEquals("null", BIG.explore(null).toString());
		assertNull(roundTrip(BIG, null));
	}

	@Test public void testBool() {
		assertEquals("true", BIG.explore(CVMBool.TRUE).toString());
		assertEquals("false", BIG.explore(CVMBool.FALSE).toString());
		assertEquals(CVMBool.TRUE, roundTrip(BIG, CVMBool.TRUE));
		assertEquals(CVMBool.FALSE, roundTrip(BIG, CVMBool.FALSE));
	}

	@Test public void testLong() {
		assertEquals("42", BIG.explore(CVMLong.create(42)).toString());
		assertEquals(CVMLong.create(42), roundTrip(BIG, CVMLong.create(42)));
		assertEquals(CVMLong.create(-1), roundTrip(BIG, CVMLong.create(-1)));
		assertEquals(CVMLong.create(0), roundTrip(BIG, CVMLong.create(0)));
	}

	@Test public void testString() {
		assertEquals("\"hello\"", BIG.explore(Strings.create("hello")).toString());
		assertEquals(Strings.create("hello"), roundTrip(BIG, Strings.create("hello")));
		assertEquals(Strings.create(""), roundTrip(BIG, Strings.create("")));
		// String containing escapable characters round-trips unchanged.
		assertEquals(Strings.create("a\"b\nc"), roundTrip(BIG, Strings.create("a\"b\nc")));
	}

	// =================================================================
	// Key formatting (OQ-1) — strict strings to test the feature itself
	// =================================================================

	@Test public void testKeywordKeyUnquotedWhenIdentifier() {
		ACell m = Maps.of(Keyword.create("name"), Strings.create("Alice"));
		// Strict: this is the OQ-1 feature under test.
		assertEquals("{name: \"Alice\"}", BIG.explore(m).toString());
	}

	@Test public void testKeywordKeyQuotedWhenNotIdentifier() {
		ACell m = Maps.of(Keyword.create("hello-world"), CVMLong.create(1));
		assertEquals("{\"hello-world\": 1}", BIG.explore(m).toString());
	}

	@Test public void testStringKeyUnquotedWhenIdentifier() {
		ACell m = Maps.of(Strings.create("active"), CVMBool.TRUE);
		assertEquals("{active: true}", BIG.explore(m).toString());
	}

	@Test public void testStringKeyQuotedWhenNotIdentifier() {
		ACell m = Maps.of(Strings.create("hello world"), CVMLong.create(1));
		assertEquals("{\"hello world\": 1}", BIG.explore(m).toString());
	}

	@Test public void testIntegerKeyAlwaysQuoted() {
		// JSON5 MemberName grammar: IdentifierName | StringLiteral.
		// Bare numeric keys are invalid JSON5 — must quote.
		ACell m = Maps.of(CVMLong.create(42), Strings.create("x"));
		assertEquals("{\"42\": \"x\"}", BIG.explore(m).toString());
		// Verify the output is valid JSON5 by re-parsing.
		parseBack(BIG.explore(m));
	}

	@Test public void testStringKeyEscapedCorrectly() {
		ACell m = Maps.of(Strings.create("a\"b"), CVMLong.create(1));
		AString out = BIG.explore(m);
		// Strict form for the escape itself.
		assertEquals("{\"a\\\"b\": 1}", out.toString());
		// And it round-trips through JSON5 with the original key recovered.
		ACell parsed = parseBack(out);
		assertEquals(Maps.of(Strings.create("a\"b"), CVMLong.create(1)), parsed);
	}

	// =================================================================
	// Empty containers — strict, four distinct forms for disambiguation
	// =================================================================

	@Test public void testEmptyFormsAreDistinct() {
		assertEquals("null", BIG.explore(null).toString());
		assertEquals("{}", BIG.explore(Maps.empty()).toString());
		assertEquals("[]", BIG.explore(Vectors.empty()).toString());
		assertEquals("[]", BIG.explore(Lists.empty()).toString());
		assertEquals("[/* Set */]", BIG.explore(Sets.empty()).toString());
	}

	// =================================================================
	// JSON5 round-trip: fit cases
	//
	// For cells that fit budget, CellExplorer output must re-load as JSON5
	// and the parsed structure must match the input (normalised for JSON5's
	// type mapping: keywords become strings, sets become sequences).
	// =================================================================

	@Test public void testFitVectorRoundTrips() {
		ACell input = Vectors.of(1, 2, 3);
		assertEquals(input, roundTrip(BIG, input));
	}

	@Test public void testFitNestedVectorRoundTrips() {
		ACell input = Vectors.of(Vectors.of(1, 2), Vectors.of(3, 4));
		assertEquals(input, roundTrip(BIG, input));
	}

	@Test public void testFitStringVectorRoundTrips() {
		ACell input = Vectors.of(Strings.create("a"), Strings.create("b"), Strings.create("c"));
		assertEquals(input, roundTrip(BIG, input));
	}

	@Test public void testFitListRoundTripsAsVector() {
		// Lists render as [...] and reparse as vectors — same shape, same elements.
		ACell input = Lists.of(1, 2, 3);
		ACell parsed = roundTrip(BIG, input);
		assertEquals(Vectors.of(1, 2, 3), parsed);
	}

	@Test public void testFitSetRoundTripsAsSequence() {
		ACell input = Sets.of(1, 2, 3);
		ACell parsed = roundTrip(BIG, input);
		ACollection<?> coll = (ACollection<?>) parsed;
		assertEquals(3L, coll.count());
		assertTrue(coll.contains(CVMLong.create(1)));
		assertTrue(coll.contains(CVMLong.create(2)));
		assertTrue(coll.contains(CVMLong.create(3)));
	}

	@Test public void testFitKeywordKeyedMapRoundTripsAsStringKeyedMap() {
		// Keyword key :name → JSON5 identifier name → string key "name" on reload.
		ACell input = Maps.of(Keyword.create("name"), Strings.create("Alice"));
		ACell parsed = roundTrip(BIG, input);
		AMap<?, ?> m = (AMap<?, ?>) parsed;
		assertEquals(1L, m.count());
		assertEquals(Strings.create("Alice"), m.get(Strings.create("name")));
	}

	@Test public void testFitMultiEntryKeywordKeyedMapRoundTrips() {
		AMap<ACell, ACell> input = Maps.empty();
		input = input.assoc(Keyword.create("a"), CVMLong.create(1));
		input = input.assoc(Keyword.create("b"), CVMLong.create(2));
		input = input.assoc(Keyword.create("c"), CVMLong.create(3));
		AMap<?, ?> parsed = (AMap<?, ?>) roundTrip(BIG, input);
		assertEquals(3L, parsed.count());
		assertEquals(CVMLong.create(1), parsed.get(Strings.create("a")));
		assertEquals(CVMLong.create(2), parsed.get(Strings.create("b")));
		assertEquals(CVMLong.create(3), parsed.get(Strings.create("c")));
	}

	@Test public void testFitIntegerKeyedMapRoundTrips() {
		ACell input = Maps.of(CVMLong.create(42), Strings.create("x"));
		AMap<?, ?> parsed = (AMap<?, ?>) roundTrip(BIG, input);
		assertEquals(1L, parsed.count());
		// Integer key is quoted in output, so parses back as a string key "42".
		assertEquals(Strings.create("x"), parsed.get(Strings.create("42")));
	}

	@Test public void testFitNestedMapRoundTrips() {
		ACell input = Maps.of(
			Keyword.create("outer"),
			Maps.of(Keyword.create("inner"), CVMLong.create(1)));
		AMap<?, ?> parsed = (AMap<?, ?>) roundTrip(BIG, input);
		AMap<?, ?> inner = (AMap<?, ?>) parsed.get(Strings.create("outer"));
		assertEquals(CVMLong.create(1), inner.get(Strings.create("inner")));
	}

	// =================================================================
	// Gap closure: nested maps inside non-map containers
	//
	// Previously, a vector / list / set containing a map delegated to JSON's
	// rendering (quoted keys). Step 6 unified this through the CellExplorer
	// renderer — nested maps now get OQ-1 treatment regardless of parent type.
	// =================================================================

	@Test public void testMapInsideVectorGetsOQ1Treatment() {
		ACell m = Maps.of(Keyword.create("name"), Strings.create("Alice"));
		ACell input = Vectors.of(CVMLong.create(1), m, CVMLong.create(3));
		AString out = BIG.explore(input);
		// The map inside the vector must use unquoted keyword key form.
		assertTrue(out.toString().contains("{name: \"Alice\"}"),
			"Expected unquoted keyword key inside vector, got: " + out);
		// And round-trip structurally.
		ACollection<?> parsed = (ACollection<?>) parseBack(out);
		assertEquals(3L, parsed.count());
	}

	@Test public void testMapInsideListGetsOQ1Treatment() {
		ACell m = Maps.of(Keyword.create("id"), CVMLong.create(7));
		ACell input = Lists.of(m);
		AString out = BIG.explore(input);
		assertTrue(out.toString().contains("{id: 7}"),
			"Expected unquoted keyword key inside list, got: " + out);
	}

	@Test public void testMapInsideSetGetsOQ1Treatment() {
		ACell m = Maps.of(Keyword.create("k"), CVMLong.create(1));
		ACell input = Sets.of(m);
		AString out = BIG.explore(input);
		assertTrue(out.toString().contains("{k: 1}"),
			"Expected unquoted keyword key inside set, got: " + out);
	}

	@Test public void testDeeplyMixedStructureRoundTrips() {
		// Map containing vector containing map containing string.
		ACell input = Maps.of(
			Keyword.create("wrap"),
			Vectors.of(Maps.of(Keyword.create("tag"), Strings.create("leaf"))));
		AMap<?, ?> parsed = (AMap<?, ?>) roundTrip(BIG, input);
		ACollection<?> v = (ACollection<?>) parsed.get(Strings.create("wrap"));
		assertEquals(1L, v.count());
		AMap<?, ?> inner = (AMap<?, ?>) v.get(0);
		assertEquals(Strings.create("leaf"), inner.get(Strings.create("tag")));
	}

	// =================================================================
	// Set marker (feature test — strict form)
	// =================================================================

	@Test public void testSetFitHasInlineMarker() {
		// Single-element set has deterministic output.
		ACell s = Sets.of(42);
		assertEquals("[42 /* Set */]", BIG.explore(s).toString());
		// And reparses (comment dropped) as a singleton sequence.
		ACollection<?> parsed = (ACollection<?>) parseBack(BIG.explore(s));
		assertEquals(1L, parsed.count());
		assertEquals(CVMLong.create(42), parsed.get(0));
	}

	@Test public void testSetEmptyHasMarker() {
		assertEquals("[/* Set */]", BIG.explore(Sets.empty()).toString());
		// Reparses as an empty sequence (marker is a comment).
		ACollection<?> parsed = (ACollection<?>) parseBack(BIG.explore(Sets.empty()));
		assertEquals(0L, parsed.count());
	}

	// =================================================================
	// Truncation: semantic properties only (no brittle format matching)
	//
	// Guarantees:
	//   1. Output is valid JSON5 (re-parses without error).
	//   2. The parsed cell has ≤ the input's element/entry count (truncation
	//      never invents data).
	//   3. Every visible element in the parsed output was in the input.
	// =================================================================

	@Test public void testLargeVectorTruncatedOutputIsValidJSON5() {
		ACell input = Vectors.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
		CellExplorer tight = new CellExplorer(40);
		AString out = tight.explore(input);
		// Always parses as JSON5.
		ACell parsed = parseBack(out);
		ACollection<?> coll = (ACollection<?>) parsed;
		// At most the input count; may be 0 if fully-truncated.
		assertTrue(coll.count() <= 10, "Parsed count exceeds input: " + coll.count());
	}

	@Test public void testLargeMapTruncatedOutputIsValidJSON5() {
		AMap<ACell, ACell> input = Maps.empty();
		for (int i = 0; i < 50; i++) {
			input = input.assoc(Keyword.create("k" + i), CVMLong.create(i));
		}
		CellExplorer tight = new CellExplorer(120);
		AString out = tight.explore(input);
		ACell parsed = parseBack(out);
		AMap<?, ?> m = (AMap<?, ?>) parsed;
		assertTrue(m.count() <= 50, "Parsed count exceeds input: " + m.count());
		assertTrue(m.count() < 50, "Expected truncation (< 50 entries): " + m.count());
	}

	@Test public void testVeryTightBudgetStillProducesValidJSON5() {
		// Budget below ANNOTATION_RESERVE should still yield parseable output.
		ACell input = Vectors.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
		CellExplorer minimal = new CellExplorer(5);
		AString out = minimal.explore(input);
		ACell parsed = parseBack(out);
		assertNotNull(parsed);
	}

	@Test public void testMapWithNonFittingValuesStillRoundTripsVisibleEntries() {
		// Values that are large relative to budget. Visible entries should
		// still resolve to something parseable.
		AMap<ACell, ACell> input = Maps.empty();
		input = input.assoc(Keyword.create("a"), Strings.create("alpha"));
		input = input.assoc(Keyword.create("b"), Strings.create("bravo"));
		input = input.assoc(Keyword.create("c"), Strings.create("charlie"));
		input = input.assoc(Keyword.create("d"), Strings.create("delta"));
		CellExplorer mid = new CellExplorer(80);
		AString out = mid.explore(input);
		ACell parsed = parseBack(out);
		AMap<?, ?> m = (AMap<?, ?>) parsed;
		// Each visible key/value must have come from the input.
		for (long i = 0; i < m.count(); i++) {
			ACell k = m.entryAt(i).getKey();
			ACell v = m.entryAt(i).getValue();
			String keyName = k.toString();
			assertTrue(keyName.equals("a") || keyName.equals("b")
				|| keyName.equals("c") || keyName.equals("d"),
				"Unexpected key: " + k);
			assertNotNull(v);
		}
	}

	@Test public void testTruncatedSetStillParsesAsSequence() {
		ACell input = Sets.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
		CellExplorer tight = new CellExplorer(30);
		AString out = tight.explore(input);
		ACollection<?> parsed = (ACollection<?>) parseBack(out);
		assertTrue(parsed.count() <= 10);
	}

	@Test public void testTruncationNeverInventsData() {
		// Build a specific map with distinct values; any value that appears
		// in the truncated output must have been in the input.
		AMap<ACell, ACell> input = Maps.empty();
		for (int i = 0; i < 30; i++) {
			input = input.assoc(Keyword.create("k" + i), CVMLong.create(i * 1000 + 17));
		}
		CellExplorer mid = new CellExplorer(100);
		ACell parsed = parseBack(mid.explore(input));
		AMap<?, ?> m = (AMap<?, ?>) parsed;
		for (long i = 0; i < m.count(); i++) {
			ACell value = m.entryAt(i).getValue();
			long v = ((CVMLong) value).longValue();
			// Values in the input are of the form i*1000 + 17 for 0 <= i < 30.
			assertTrue(v >= 17 && v <= 29017 && (v - 17) % 1000 == 0,
				"Invented value: " + v);
		}
	}

	// =================================================================
	// Configuration and reusability
	// =================================================================

	@Test public void testCompactConstructor() {
		// Compact flag doesn't change output in v1 (pretty-print not yet
		// implemented), but the constructor must accept it.
		CellExplorer explorer = new CellExplorer(1000, true);
		assertEquals("42", explorer.explore(CVMLong.create(42)).toString());
	}

	@Test public void testExplorerReusableAcrossCalls() {
		assertEquals("1", BIG.explore(CVMLong.create(1)).toString());
		assertEquals("2", BIG.explore(CVMLong.create(2)).toString());
		assertEquals("[1, 2, 3]", BIG.explore(Vectors.of(1, 2, 3)).toString());
	}
}
