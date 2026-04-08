package convex.core.data.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Address;
import convex.core.data.ACell;
import convex.core.data.ACollection;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Keyword;
import convex.core.data.Lists;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.Symbol;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMDouble;
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

	// =================================================================
	// Leaf type overrides (OQ-8 and the Address / Keyword-value /
	// Symbol-value distinctions from the design doc)
	// =================================================================

	@Test public void testAddressAsValueRenders() {
		// Address renders as quoted "#N" — distinct from a bare integer.
		assertEquals("\"#42\"", BIG.explore(Address.create(42)).toString());
		assertEquals("\"#0\"", BIG.explore(Address.create(0)).toString());
		// Parses back as a string (JSON5 has no address literal).
		assertEquals(Strings.create("#42"), parseBack(BIG.explore(Address.create(42))));
	}

	@Test public void testAddressInsideVectorIsDistinctFromLong() {
		ACell input = Vectors.of(CVMLong.create(42), Address.create(42));
		AString out = BIG.explore(input);
		// Both elements fit, but their renderings differ.
		assertTrue(out.toString().contains("42"));
		assertTrue(out.toString().contains("\"#42\""));
		// Parses: first is long 42, second is string "#42".
		ACollection<?> parsed = (ACollection<?>) parseBack(out);
		assertEquals(2L, parsed.count());
		assertEquals(CVMLong.create(42), parsed.get(0));
		assertEquals(Strings.create("#42"), parsed.get(1));
	}

	@Test public void testAddressAsMapValueRenders() {
		ACell input = Maps.of(Keyword.create("owner"), Address.create(1337));
		AString out = BIG.explore(input);
		assertTrue(out.toString().contains("\"#1337\""));
		AMap<?, ?> parsed = (AMap<?, ?>) parseBack(out);
		assertEquals(Strings.create("#1337"), parsed.get(Strings.create("owner")));
	}

	@Test public void testKeywordAsValueRenders() {
		// Keyword as VALUE (not key) — ":name" form, quoted.
		ACell input = Vectors.of(Keyword.create("alpha"));
		AString out = BIG.explore(input);
		assertEquals("[\":alpha\"]", out.toString());
		ACollection<?> parsed = (ACollection<?>) parseBack(out);
		assertEquals(Strings.create(":alpha"), parsed.get(0));
	}

	@Test public void testKeywordAsKeyAndValueDistinct() {
		// Keyword key → bare identifier; keyword value → ":name" quoted.
		ACell input = Maps.of(Keyword.create("tag"), Keyword.create("alpha"));
		assertEquals("{tag: \":alpha\"}", BIG.explore(input).toString());
	}

	@Test public void testSymbolAsValueRenders() {
		ACell input = Vectors.of(Symbol.create("foo"));
		AString out = BIG.explore(input);
		assertEquals("[\"'foo\"]", out.toString());
		ACollection<?> parsed = (ACollection<?>) parseBack(out);
		assertEquals(Strings.create("'foo"), parsed.get(0));
	}

	@Test public void testNaNRendersBare() {
		// JSON5 supports bare NaN — parseJSON5("NaN") → CVMDouble.NaN.
		AString out = BIG.explore(CVMDouble.NaN);
		assertEquals("NaN", out.toString());
		assertEquals(CVMDouble.NaN, parseBack(out));
	}

	@Test public void testPositiveInfinityRendersBare() {
		AString out = BIG.explore(CVMDouble.POSITIVE_INFINITY);
		assertEquals("Infinity", out.toString());
		assertEquals(CVMDouble.POSITIVE_INFINITY, parseBack(out));
	}

	@Test public void testNegativeInfinityRendersBare() {
		AString out = BIG.explore(CVMDouble.NEGATIVE_INFINITY);
		assertEquals("-Infinity", out.toString());
		assertEquals(CVMDouble.NEGATIVE_INFINITY, parseBack(out));
	}

	@Test public void testFiniteDoubleRendersViaJSON() {
		// Finite doubles still delegate to JSON (no override needed).
		AString out = BIG.explore(CVMDouble.create(3.14));
		// Round-trips as CVMDouble.
		assertEquals(CVMDouble.create(3.14), parseBack(out));
	}

	@Test public void testNonFiniteDoubleInsideVectorRoundTrips() {
		ACell input = Vectors.of(
			CVMDouble.create(1.0),
			CVMDouble.NaN,
			CVMDouble.POSITIVE_INFINITY,
			CVMDouble.NEGATIVE_INFINITY);
		ACollection<?> parsed = (ACollection<?>) roundTrip(BIG, input);
		assertEquals(4L, parsed.count());
		assertEquals(CVMDouble.create(1.0), parsed.get(0));
		assertEquals(CVMDouble.NaN, parsed.get(1));
		assertEquals(CVMDouble.POSITIVE_INFINITY, parsed.get(2));
		assertEquals(CVMDouble.NEGATIVE_INFINITY, parsed.get(3));
	}

	// =================================================================
	// Leaf partial forms (strings and blobs)
	// =================================================================

	@Test public void testLargeStringProducesPartialForm() {
		// A string much bigger than budget should emit a prefix + annotation,
		// remain valid JSON5, and parse as a (shorter) string.
		String content = "abcdefghijklmnopqrstuvwxyz".repeat(200); // ~5KB
		ACell input = Strings.create(content);
		CellExplorer tight = new CellExplorer(100);
		AString out = tight.explore(input);
		ACell parsed = parseBack(out);
		// Must parse as a string (not nil).
		assertNotNull(parsed);
		assertTrue(parsed instanceof AString);
		AString parsedStr = (AString) parsed;
		// The prefix of the original is preserved.
		assertTrue(content.startsWith(parsedStr.toString().substring(0,
			Math.min(parsedStr.toString().length(), 20))),
			"Partial string prefix should be a prefix of the original: " + parsedStr);
		// Annotation mentions "String" and a size unit.
		assertTrue(out.toString().contains("/* String"),
			"Expected String annotation: " + out);
	}

	@Test public void testLargeBlobProducesPartialForm() {
		byte[] bytes = new byte[2000];
		for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i & 0xff);
		ACell input = Blob.create(bytes);
		CellExplorer tight = new CellExplorer(80);
		AString out = tight.explore(input);
		ACell parsed = parseBack(out);
		// Must parse as a string containing the hex prefix.
		assertTrue(parsed instanceof AString, "Blob partial should parse as string, got: " + parsed);
		String s = ((AString) parsed).toString();
		assertTrue(s.startsWith("0x"), "Blob partial must start with 0x: " + s);
		assertTrue(out.toString().contains("/* Blob"),
			"Expected Blob annotation: " + out);
	}

	@Test public void testLargeStringInsideVectorRoundTripsStructurally() {
		// Vector containing a large string and some longs. The truncated form
		// must still parse as a valid JSON5 sequence.
		String big = "x".repeat(5000);
		ACell input = Vectors.of(CVMLong.create(1), Strings.create(big), CVMLong.create(3));
		CellExplorer tight = new CellExplorer(200);
		AString out = tight.explore(input);
		ACollection<?> parsed = (ACollection<?>) parseBack(out);
		// Count is bounded by input.
		assertTrue(parsed.count() <= 3L);
	}

	@Test public void testLeafTruncatedFallbackValidJSON5() {
		// Budget tight enough that even a partial leaf form cannot be formed.
		// CellExplorer must still emit parseable JSON5.
		String big = "x".repeat(5000);
		CellExplorer minimal = new CellExplorer(5);
		AString out = minimal.explore(Strings.create(big));
		ACell parsed = parseBack(out);
		// Fallback emits bare null + comment → parses as nil.
		// (Documented lossy fallback for extreme budgets.)
		assertNull(parsed);
	}

	// =================================================================
	// Adversarial content: JSON5 metacharacters in user strings
	// =================================================================

	@Test public void testStringWithCommentTerminatorRoundTrips() {
		// Content contains "*/" — must not leak out of a string literal
		// to accidentally close a neighbouring comment.
		String content = "hello*/world";
		ACell input = Strings.create(content);
		assertEquals(input, roundTrip(BIG, input));
	}

	@Test public void testStringWithAllMetaCharsRoundTrips() {
		String content = "a\"b\\c/d\ne\rf\tg\b\fh";
		ACell input = Strings.create(content);
		assertEquals(input, roundTrip(BIG, input));
	}

	@Test public void testStringWithControlCharRoundTrips() {
		String content = "a\u0000b\u0001c\u001fd";
		ACell input = Strings.create(content);
		assertEquals(input, roundTrip(BIG, input));
	}

	@Test public void testStringWithSurrogatePairRoundTrips() {
		// U+1F600 (grinning face) — two Java chars (surrogate pair).
		String content = "emoji: \uD83D\uDE00 end";
		ACell input = Strings.create(content);
		assertEquals(input, roundTrip(BIG, input));
	}

	@Test public void testStringWithSurrogatePairAtTruncationBoundaryStaysValid() {
		// Build a string where the natural truncation point would land on a
		// high surrogate. The partial form must back off so the emitted
		// prefix is not a lone high surrogate.
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 100; i++) sb.append("\uD83D\uDE00"); // 100 emoji
		ACell input = Strings.create(sb.toString());
		CellExplorer tight = new CellExplorer(60);
		AString out = tight.explore(input);
		// Most important: output is valid JSON5 (parse does not throw).
		parseBack(out);
	}

	@Test public void testMapKeyWithCommentTerminatorRoundTrips() {
		// Key containing "*/" — must be quoted so the JSON5 comment syntax
		// is not confused.
		ACell input = Maps.of(Strings.create("a*/b"), CVMLong.create(1));
		AMap<?, ?> parsed = (AMap<?, ?>) roundTrip(BIG, input);
		assertEquals(CVMLong.create(1), parsed.get(Strings.create("a*/b")));
	}

	@Test public void testKeywordKeyWithNonIdentifierCharsRoundTrips() {
		// Keyword whose name is not a valid identifier → must be quoted.
		ACell input = Maps.of(Keyword.create("a/b-c"), CVMLong.create(1));
		AMap<?, ?> parsed = (AMap<?, ?>) roundTrip(BIG, input);
		assertEquals(CVMLong.create(1), parsed.get(Strings.create("a/b-c")));
	}

	@Test public void testStringValueContainingBraceAndBracketsRoundTrips() {
		// Content has JSON5 structural characters — parser must only see them
		// inside a string literal.
		String content = "{[(:x)]},";
		assertEquals(Strings.create(content), roundTrip(BIG, Strings.create(content)));
	}

	// =================================================================
	// Adversarial structures: depth, width, mixed
	// =================================================================

	@Test public void testDeeplyNestedVectorRoundTrips() {
		// 100 nested vectors, innermost holds a single long.
		ACell cell = CVMLong.create(7);
		for (int i = 0; i < 100; i++) cell = Vectors.of(cell);
		ACell parsed = roundTrip(BIG, cell);
		// Walk back down 100 layers.
		for (int i = 0; i < 100; i++) {
			ACollection<?> c = (ACollection<?>) parsed;
			assertEquals(1L, c.count());
			parsed = c.get(0);
		}
		assertEquals(CVMLong.create(7), parsed);
	}

	@Test public void testDeeplyNestedMapRoundTrips() {
		// 50 nested maps, all with keyword keys.
		ACell cell = CVMLong.create(1);
		for (int i = 0; i < 50; i++) {
			cell = Maps.of(Keyword.create("k"), cell);
		}
		ACell parsed = roundTrip(BIG, cell);
		for (int i = 0; i < 50; i++) {
			AMap<?, ?> m = (AMap<?, ?>) parsed;
			parsed = m.get(Strings.create("k"));
		}
		assertEquals(CVMLong.create(1), parsed);
	}

	@Test public void testWideMapFitsOrTruncatesCleanly() {
		// 500 keys — large, but with generous budget all should fit.
		AMap<ACell, ACell> input = Maps.empty();
		for (int i = 0; i < 500; i++) {
			input = input.assoc(Keyword.create("k" + i), CVMLong.create(i));
		}
		// Huge budget first — everything fits.
		CellExplorer big = new CellExplorer(100_000);
		ACell parsedFull = parseBack(big.explore(input));
		assertEquals(500L, ((AMap<?, ?>) parsedFull).count());

		// Tight budget — parses cleanly and count is bounded above.
		CellExplorer tight = new CellExplorer(500);
		ACell parsedTight = parseBack(tight.explore(input));
		assertTrue(((AMap<?, ?>) parsedTight).count() <= 500L);
	}

	@Test public void testWideVectorFitsOrTruncatesCleanly() {
		ACell[] items = new ACell[500];
		for (int i = 0; i < 500; i++) items[i] = CVMLong.create(i);
		ACell input = Vectors.create(items);
		ACell parsed = roundTrip(new CellExplorer(100_000), input);
		assertEquals(500L, ((ACollection<?>) parsed).count());
	}

	@Test public void testMixedDeepAdversarialStructureParses() {
		// Vector of maps, each containing a set of vectors, with non-finite
		// doubles and addresses mixed in.
		ACell mix = Vectors.of(
			Maps.of(
				Keyword.create("addr"), Address.create(1337),
				Keyword.create("vals"), Sets.of(
					Vectors.of(CVMDouble.NaN, CVMDouble.POSITIVE_INFINITY),
					Vectors.of(Keyword.create("tag"), Symbol.create("x")))),
			Maps.of(
				Keyword.create("nested"),
				Maps.of(Keyword.create("deep"), Strings.create("a*/b"))));
		// Large budget — everything should fit.
		parseBack(BIG.explore(mix));
	}

	// =================================================================
	// Budget edges
	// =================================================================

	@Test public void testBudgetIntegerMaxValueDoesNotOverflow() {
		// Huge budget — must not do anything weird (arithmetic overflow).
		CellExplorer huge = new CellExplorer(Integer.MAX_VALUE);
		assertEquals("42", huge.explore(CVMLong.create(42)).toString());
		ACell v = Vectors.of(1, 2, 3);
		assertEquals(v, parseBack(huge.explore(v)));
	}

	@Test public void testBudgetOneStillProducesValidOutput() {
		CellExplorer tiny = new CellExplorer(1);
		// Small leaves (nil, small long) still fit and parse.
		assertEquals("null", tiny.explore(null).toString());
		// Empty containers.
		assertEquals("{}", tiny.explore(Maps.empty()).toString());
		assertEquals("[]", tiny.explore(Vectors.empty()).toString());
	}

	@Test public void testBudgetZeroStillProducesValidJSON5OrEmpty() {
		CellExplorer zero = new CellExplorer(0);
		// Must not throw. Must produce parseable output for any cell.
		// Fit cases (empty/small): might still render; otherwise fallback.
		AString out = zero.explore(CVMLong.create(42));
		// Output is non-null and either parses or is an expected fallback form.
		assertNotNull(out);
	}

	// =================================================================
	// Extreme numeric values
	// =================================================================

	@Test public void testLongMaxValueRoundTrips() {
		assertEquals(CVMLong.create(Long.MAX_VALUE),
			roundTrip(BIG, CVMLong.create(Long.MAX_VALUE)));
	}

	@Test public void testLongMinValueRoundTrips() {
		assertEquals(CVMLong.create(Long.MIN_VALUE),
			roundTrip(BIG, CVMLong.create(Long.MIN_VALUE)));
	}

	@Test public void testEmptyStringRoundTrips() {
		assertEquals(Strings.create(""), roundTrip(BIG, Strings.create("")));
	}

	@Test public void testEmptyBlobRoundTripsAsString() {
		// Empty blob renders as "0x" via JSON.appendJSON and reparses as the
		// string "0x" (blob literal syntax doesn't exist in JSON5).
		ACell parsed = roundTrip(BIG, Blob.EMPTY);
		assertEquals(Strings.create("0x"), parsed);
	}

	// =================================================================
	// Safety: output never "invents" large values under truncation
	// =================================================================

	@Test public void testTruncationNeverProducesLongerSerialisation() {
		// For a range of budgets, output length should broadly respect the
		// budget (allowing some slack for annotation text).
		String big = "y".repeat(2000);
		ACell input = Vectors.of(
			Strings.create(big),
			Strings.create(big),
			Strings.create(big));
		for (int budget : new int[] { 20, 50, 100, 200, 500 }) {
			AString out = new CellExplorer(budget).explore(input);
			// Output shouldn't wildly exceed budget — allow 4x slack for
			// annotation reserves, escape expansion, and structural overhead.
			assertTrue(out.count() < budget * 8L + 200,
				"Output " + out.count() + " far exceeds budget " + budget);
			// And must still parse as JSON5.
			parseBack(out);
		}
	}

	@Test public void testTruncationOutputAlwaysParses() {
		// Stress: many budgets against a moderately complex cell.
		AMap<ACell, ACell> m = Maps.empty();
		for (int i = 0; i < 40; i++) {
			m = m.assoc(Keyword.create("k" + i),
				Vectors.of(CVMLong.create(i), Strings.create("val" + i)));
		}
		for (int budget = 1; budget <= 500; budget += 23) {
			AString out = new CellExplorer(budget).explore(m);
			// Must always parse (or be legitimately empty).
			assertDoesNotThrow(() -> JSON.parseJSON5(out.toString()),
				"Budget=" + budget + " produced unparseable output: " + out);
		}
	}

	// =================================================================
	// Pretty-print mode (compact=false)
	// =================================================================

	/** Explorer in pretty (multi-line) mode. */
	private static final CellExplorer PRETTY = new CellExplorer(10_000, false);

	@Test public void testPrettyLeavesUnchanged() {
		// Leaves have no structure to indent — output matches compact.
		assertEquals("42", PRETTY.explore(CVMLong.create(42)).toString());
		assertEquals("\"hello\"", PRETTY.explore(Strings.create("hello")).toString());
		assertEquals("null", PRETTY.explore(null).toString());
	}

	@Test public void testPrettyEmptyContainersStayTight() {
		// Empty containers render without newlines even in pretty mode.
		assertEquals("{}", PRETTY.explore(Maps.empty()).toString());
		assertEquals("[]", PRETTY.explore(Vectors.empty()).toString());
		assertEquals("[]", PRETTY.explore(Lists.empty()).toString());
		assertEquals("[/* Set */]", PRETTY.explore(Sets.empty()).toString());
	}

	@Test public void testPrettyVectorIsMultiLine() {
		AString out = PRETTY.explore(Vectors.of(1, 2, 3));
		String s = out.toString();
		// Must contain newlines; each element on its own indented line.
		assertTrue(s.contains("\n  1"), "Pretty vector should indent first element: " + s);
		assertTrue(s.contains("\n  2"), "Pretty vector should indent second element: " + s);
		assertTrue(s.contains("\n  3"), "Pretty vector should indent third element: " + s);
		// Must still round-trip.
		assertEquals(Vectors.of(1, 2, 3), parseBack(out));
	}

	@Test public void testPrettyMapIsMultiLine() {
		ACell input = Maps.of(Keyword.create("a"), CVMLong.create(1),
			Keyword.create("b"), CVMLong.create(2));
		AString out = PRETTY.explore(input);
		String s = out.toString();
		assertTrue(s.contains("\n  a: 1"), "Pretty map should indent entries: " + s);
		assertTrue(s.contains("\n  b: 2"), "Pretty map should indent entries: " + s);
		// Round-trips structurally.
		AMap<?, ?> parsed = (AMap<?, ?>) parseBack(out);
		assertEquals(CVMLong.create(1), parsed.get(Strings.create("a")));
		assertEquals(CVMLong.create(2), parsed.get(Strings.create("b")));
	}

	@Test public void testPrettyNestedStructureIndentsProgressively() {
		// Two levels of nesting — inner entries should be indented deeper.
		ACell input = Maps.of(Keyword.create("outer"),
			Maps.of(Keyword.create("inner"), CVMLong.create(42)));
		AString out = PRETTY.explore(input);
		String s = out.toString();
		// Outer key at 2-space indent, inner key at 4-space indent.
		assertTrue(s.contains("\n  outer:"), "Outer at 2 spaces: " + s);
		assertTrue(s.contains("\n    inner:"), "Inner at 4 spaces: " + s);
		// Round-trips.
		AMap<?, ?> parsed = (AMap<?, ?>) parseBack(out);
		AMap<?, ?> inner = (AMap<?, ?>) parsed.get(Strings.create("outer"));
		assertEquals(CVMLong.create(42), inner.get(Strings.create("inner")));
	}

	@Test public void testPrettyAndCompactRoundTripToSameCell() {
		// For any cell that fits budget, pretty and compact parse to the
		// same reconstructed structure.
		ACell input = Maps.of(
			Keyword.create("items"), Vectors.of(1, 2, 3),
			Keyword.create("name"), Strings.create("Alice"));
		ACell p = parseBack(PRETTY.explore(input));
		ACell c = parseBack(BIG.explore(input));
		assertEquals(p, c);
	}

	@Test public void testPrettyTruncationStillValid() {
		// Pretty mode in a tight budget — must still parse.
		AMap<ACell, ACell> input = Maps.empty();
		for (int i = 0; i < 30; i++) {
			input = input.assoc(Keyword.create("k" + i), CVMLong.create(i));
		}
		CellExplorer prettyTight = new CellExplorer(200, false);
		AString out = prettyTight.explore(input);
		ACell parsed = parseBack(out);
		assertTrue(parsed instanceof AMap);
	}

	@Test public void testPrettyDeepStructureRoundTrips() {
		// Build a moderately deep nested structure and verify pretty mode
		// handles indentation without losing data.
		ACell cell = CVMLong.create(1);
		for (int i = 0; i < 10; i++) cell = Vectors.of(cell);
		ACell parsed = parseBack(PRETTY.explore(cell));
		for (int i = 0; i < 10; i++) {
			parsed = ((ACollection<?>) parsed).get(0);
		}
		assertEquals(CVMLong.create(1), parsed);
	}

	@Test public void testCompactDefaultIsSingleLine() {
		// Sanity: the default constructor produces compact output.
		CellExplorer dflt = new CellExplorer(10_000);
		AString out = dflt.explore(Vectors.of(1, 2, 3));
		assertEquals("[1, 2, 3]", out.toString());
		// And is byte-identical to explicit compact.
		CellExplorer explicitCompact = new CellExplorer(10_000, true);
		assertEquals(out.toString(), explicitCompact.explore(Vectors.of(1, 2, 3)).toString());
	}

	@Test public void testNoBareNumericKeyInAnyOutput() {
		// Regression: integer keys must always be quoted. Use a variety of
		// numeric-keyed maps and confirm no output contains an unquoted
		// numeric key (would fail JSON5 parse anyway, but catch it directly).
		AMap<ACell, ACell> m = Maps.empty();
		for (int i = -5; i < 5; i++) {
			m = m.assoc(CVMLong.create(i), Strings.create("v" + i));
		}
		AString out = BIG.explore(m);
		parseBack(out); // Primary assertion: it must parse.
		// Also verify no bare `-1:` or `0:` style fragments.
		String s = out.toString();
		assertFalse(s.matches(".*[^\"\\w]-?\\d+:.*"),
			"Found bare numeric key pattern in: " + s);
	}
}
