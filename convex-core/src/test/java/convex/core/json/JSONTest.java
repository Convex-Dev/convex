package convex.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.ErrorCodes;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.Symbols;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Index;
import convex.core.data.Lists;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.StringShort;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBigInteger;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMChar;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.ParseException;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.core.util.Utils;

public class JSONTest {

	/** Quick test function for error messages */
	public static void main(String[] args) {
		String[] inputs = {
			"{\"a\":{\"b\":", "{\"a\":{", "{\"a\":[}", "[1,2,",
			"{\"a\": }", "}{", "{\"a\":\"b\"}}", "", "{true: 1}",
			"{\n  \"a\": 1,\n  \"b\": }\n}",
		};
		for (String input : inputs) {
			try { JSON5Reader.read(input); System.out.println("  OK: \"" + input + "\"");
			} catch (ParseException e) { System.out.println("  \"" + input + "\" => " + e.getMessage()); }
		}
		System.out.println("--- JSONReader ---");
		for (String input : inputs) {
			try { JSONReader.read(input); System.out.println("  OK: \"" + input + "\"");
			} catch (ParseException e) { System.out.println("  \"" + input + "\" => " + e.getMessage()); }
		}
	}

	@Test public void testPrint() {
		assertEquals("null",JSON.toString(null));
		
		assertEquals("[]",JSON.toString(Vectors.empty()));
		assertEquals("[]",JSON.toString(Sets.empty()));
		assertEquals("{}",JSON.toString(Maps.empty()));
	
		assertEquals("\"nil\"",JSON.toString("nil"));

		assertEquals("[1,2,null]",JSON.toString(Vectors.of(1,2,null)));

		assertEquals("true",JSON.toString(true));
		assertEquals("false",JSON.toString(false));
		assertEquals("true",JSON.toString(CVMBool.TRUE));
		assertEquals("false",JSON.toString(CVMBool.FALSE));
		
		assertEquals("\"\\n\"",JSON.toString("\n"));
		assertEquals("\" \\\" \"",JSON.toString(" \" "));
		
		assertEquals("\"foo\"",JSON.toString(Symbols.FOO));
		assertEquals("\"foo\"",JSON.toString(Keywords.FOO));
		assertEquals("\"CAST\"",JSON.toString(ErrorCodes.CAST));

	}

	@Test
	public void parseStrict() {
		assertNull(JSON.parse("  null  "));
		assertEquals(CVMBool.TRUE,JSON.parseJSON5("true"));
		assertEquals(CVMLong.ONE,JSON.parse("1"));
		assertEquals(Strings.NULL,JSON.parse("\"null\""));

		assertEquals(Vectors.of(1,Maps.empty()),JSON.parseJSON5("[1,{}]"));

		
	}

	@Test
	public void testParseJSON5() {
		assertNull(JSON.parseJSON5("null"));
		assertEquals(CVMBool.TRUE,JSON.parseJSON5("true"));
		assertEquals(CVMBool.FALSE,JSON.parseJSON5("   false  "));
		
		assertSame(Vectors.empty(),JSON.parseJSON5("[]"));
		assertEquals(Vectors.of(true,null),JSON.parseJSON5("[true,null]"));
		assertEquals(Vectors.of(1,2),JSON.parseJSON5("[1,2]"));

		assertEquals(CVMLong.MAX_VALUE,JSON.parseJSON5("9223372036854775807"));
		assertEquals(CVMBigInteger.MIN_POSITIVE,JSON.parseJSON5("9223372036854775808"));

		
		assertSame(CVMLong.ONE,JSON.parseJSON5("1"));
		assertEquals(CVMDouble.ONE,JSON.parseJSON5("1.0"));
		assertEquals(CVMDouble.create(-200.0),JSON.parseJSON5("-.2e3"));

		assertEquals(Strings.NIL,JSON.parseJSON5("\"nil\""));

		assertSame(Maps.empty(),JSON.parseJSON5("{}"));
		assertSame(Maps.empty(),JSON.parseJSON5("{ /* foo */ } /*bar*/ /*baz*/"));
		assertEquals(Maps.of(Strings.NIL,1),JSON.parseJSON5("{\"nil\": 1}"));
		assertEquals(Maps.of(Strings.NIL,1),JSON.parseJSON5("{nil: 1}"));
		assertEquals(Maps.of(Strings.EMPTY,Vectors.empty()),JSON.parseJSON5("{\"\": []}"));
	
		// Some errors
		assertThrows(Exception.class,()->JSON.parseJSON5("{foo:bar}")); // TODO: Why not ParseException?
		
		// Trailing commas allowed
		assertEquals(JSON.parseJSON5("[3]"),JSON.parseJSON5("[3,]"));
		assertEquals(JSON.parseJSON5("[1, 3]"),JSON.parseJSON5("[1,3, ]"));
		assertEquals(JSON.parseJSON5("{\"foo\":1}"),JSON.parseJSON5("{\"foo\":1,}"));


		// Special cases
		assertEquals(Strings.create("a\"b"),JSON.parseJSON5("\"a\\\"b\""));

	}
	
	@Test
	public void testEscape() {
		assertEquals("\\n",JSON.escape("\n").toString());
		assertEquals(StringShort.create(" \\\""),JSON.escape(" \""));
	}
	
	@Test
	public void testPrettyJSON() {
		assertEquals("{\n  \"foo\": \"bar\"\n}",JSON.printPretty(Maps.of("foo","bar")).toString());
		
		// special case regression test
		assertEquals("\"\\\\/\"",JSON.toStringPretty(Strings.create("\\/")));
		assertEquals(Strings.create("\"\\\\/\""),JSON.printPretty(Strings.create("\\/")));

	}
	
	@Test
	public void testJSON() {
		assertNull(JSON.json(null));
		
		assertEquals((Long)13L,JSON.json(Address.create(13)));
		assertEquals("0xcafebabe",JSON.json(Blob.fromHex("cafebabe")));
		assertEquals("0x",JSON.json(Blobs.empty()));
		assertEquals("{}",JSON.json(Index.none()).toString());
		assertEquals("{}",JSON.json(Maps.empty()).toString());
		assertEquals("[1, 2]",JSON.json(Vectors.of(1,2)).toString());
		assertEquals("[1, 2]",JSON.json(Lists.of(1,2)).toString());
		assertEquals("c",JSON.json(CVMChar.create('c')));

		assertEquals("foo",JSON.json(Symbols.FOO));
		assertEquals("foo",JSON.json(Keywords.FOO));
		
		// Note keywords get colon removed when used as JSON key
		assertEquals("bar",JSON.jsonMap(Maps.of(Keywords.FOO, Keywords.BAR)).get("foo"));

		
		// JSON should convert keys to strings
		assertEquals(Maps.of("1",2), RT.cvm(JSON.json(Maps.of(1,2))));
		assertEquals(Maps.of("[]",3), RT.cvm(JSON.json(Maps.of(Vectors.empty(),3))));
		assertEquals(Maps.of("[\"\" 3]",4), RT.cvm(JSON.json(Maps.of(Vectors.of("",3),4))));
	}
	
	@Test
	public void testJSONObjects() {
		assertEquals(Maps.of("1",2), JSONReader.read("{\"1\" : 2}"));
		assertEquals(CVMLong.ONE,JSONReader.read("1"));
		assertNull(JSONReader.read("  null"));
		assertEquals(Strings.COLON,JSONReader.read("\":\""));
		assertEquals(Vectors.of(1,2),JSONReader.read("[1,2]"));

		JSONReader.read("{\"a\":[{\"b\":\"c\"},2]}");

		assertThrows(ParseException.class,()->JSONReader.readObject("[]")); // not an object
	}

	/**
	 * Malformed JSON via JSONReader must throw ParseException, never internal errors.
	 * Same ANTLR listener unwinding issue as JSON5Reader.
	 */
	@Test
	public void testMalformedJSONReaderNeverThrowsInternalError() {
		String[] malformed = {
			"{\"a\":{\"b\":",
			"{\"a\":{",
			"{}}}",
			"{\"a\":\"b\"}}",
			"}{",
			"{",
			"}",
			"",
			"   ",
			"{\"a\":[}",
			"[{]",
			"{\"a\":{\"b\":{\"c\":",
		};

		for (String input : malformed) {
			try {
				JSONReader.read(input);
			} catch (ParseException e) {
				// Expected
			} catch (Exception e) {
				fail("JSONReader.read(\"" + input + "\") threw " + e.getClass().getSimpleName()
					+ " instead of ParseException: " + e.getMessage());
			}
		}
	}
	
	@Test 
	public void testStrings() {
		assertEquals(Strings.intern("\n"),JSONReader.read("\"\\n\"")); 

		// '\/' is valid JSON, you can unnecessarily escape a slash. WTF JSON?
		assertEquals(Strings.intern("/"),JSONReader.read("\"\\/\"")); 
		
		assertEquals("\\",JSON.parse("\"\\\\\"").toString()); // i.e. '\'
		
		doStringTest("");
		doStringTest("foo bar");
		doStringTest("\\\\"); // i.e. '\'
		doStringTest("\\\""); // i.e. '"'
		doStringTest("\\u1111"); // i.e. 'x'
		doStringTest("\\/"); // i.e. '/'

	}
	
	private void doStringTest(String s) {
		String quoted="\""+s+"\"";
		AString js=JSON.parse(quoted);
		assertEquals(js,JSON.parse(JSON.toString(js)));
	}

	@Test
	public void testJSON5Comments() {
		assertEquals(Vectors.of(true,null),JSON.parseJSON5("[true, /* \n */ null]"));
		assertEquals(RT.cvm(12),JSON.parseJSON5("12 //foo"));
		assertEquals(RT.cvm(12),JSON.parseJSON5("//foo \n 12"));
		assertEquals(RT.cvm(12),JSON.parseJSON5("//foo /* \n 12"));

		assertThrows(ParseException.class,()->JSON.parseJSON5("/* 67")); // comment not closed
		assertThrows(ParseException.class,()->JSON.parseJSON5("//")); // no value
		
	}
	
	@Test
	public void testJSONDoubles() {
		assertEquals(CVMDouble.POSITIVE_INFINITY,JSON.parseJSON5("Infinity"));
		assertEquals(CVMDouble.POSITIVE_INFINITY,JSON.parseJSON5("+Infinity"));
		assertEquals(CVMDouble.NEGATIVE_INFINITY,JSON.parseJSON5("-Infinity"));
		assertEquals(CVMDouble.NEGATIVE_INFINITY,JSON.parseJSON5(" -Infinity"));
		assertEquals(CVMDouble.NaN,JSON.parseJSON5(" NaN"));


	}

	/**
	 * Strict JSON has no representation for non-finite doubles.
	 * JSON.appendJSON must emit null for NaN and ±Infinity so output is
	 * parseable by strict parsers. Regression for #547.
	 */
	@Test
	public void testAppendJSONNonFiniteDoubles() {
		assertEquals("null", JSON.toString(Double.NaN));
		assertEquals("null", JSON.toString(Double.POSITIVE_INFINITY));
		assertEquals("null", JSON.toString(Double.NEGATIVE_INFINITY));

		assertEquals("null", JSON.toString(CVMDouble.NaN));
		assertEquals("null", JSON.toString(CVMDouble.POSITIVE_INFINITY));
		assertEquals("null", JSON.toString(CVMDouble.NEGATIVE_INFINITY));

		// Output must round-trip as strict JSON (back to nil)
		assertNull(JSON.parse(JSON.toString(CVMDouble.NaN)));
		assertNull(JSON.parse(JSON.toString(CVMDouble.POSITIVE_INFINITY)));
		assertNull(JSON.parse(JSON.toString(CVMDouble.NEGATIVE_INFINITY)));
	}

	/**
	 * JSON5 writer path (#546) — non-finite doubles round-trip through
	 * JSON5Reader as JSON5 literals, all other types render identically to
	 * appendJSON, and nested non-finite doubles are preserved.
	 */
	@Test
	public void testAppendJSON5() {
		// Non-finite CVMDouble literals
		assertEquals("NaN", JSON.printJSON5(CVMDouble.NaN).toString());
		assertEquals("Infinity", JSON.printJSON5(CVMDouble.POSITIVE_INFINITY).toString());
		assertEquals("-Infinity", JSON.printJSON5(CVMDouble.NEGATIVE_INFINITY).toString());

		// Round-trip via JSON5Reader preserves non-finite values
		assertEquals(CVMDouble.NaN, JSON.parseJSON5(JSON.printJSON5(CVMDouble.NaN)));
		assertEquals(CVMDouble.POSITIVE_INFINITY,
			JSON.parseJSON5(JSON.printJSON5(CVMDouble.POSITIVE_INFINITY)));
		assertEquals(CVMDouble.NEGATIVE_INFINITY,
			JSON.parseJSON5(JSON.printJSON5(CVMDouble.NEGATIVE_INFINITY)));

		// Finite doubles render the same as strict JSON
		assertEquals(JSON.toString(CVMDouble.ONE), JSON.printJSON5(CVMDouble.ONE).toString());

		// Non-double types render identically in JSON and JSON5
		assertEquals(JSON.toString(Maps.of("a", 1)),
			JSON.printJSON5(Maps.of("a", 1)).toString());
		assertEquals(JSON.toString(Vectors.of(1, 2, 3)),
			JSON.printJSON5(Vectors.of(1, 2, 3)).toString());

		// Nested non-finite doubles propagate through containers
		ACell nested = Maps.of("x", Vectors.of(CVMDouble.NaN, CVMDouble.POSITIVE_INFINITY));
		assertEquals(nested, JSON.parseJSON5(JSON.printJSON5(nested)));

		// nil remains null in JSON5
		assertEquals("null", JSON.printJSON5(null).toString());
	}

	/**
	 * Direct coverage for JSON5Reader non-finite double literals via both
	 * String and InputStream entry points (JSON5 spec compliance).
	 */
	@Test
	public void testJSON5ReaderNonFiniteDoubles() throws IOException {
		// String entry point
		assertEquals(CVMDouble.NaN, JSON5Reader.read("NaN"));
		assertEquals(CVMDouble.POSITIVE_INFINITY, JSON5Reader.read("Infinity"));
		assertEquals(CVMDouble.NEGATIVE_INFINITY, JSON5Reader.read("-Infinity"));

		// Verify underlying IEEE 754 values
		assertEquals(Double.POSITIVE_INFINITY, ((CVMDouble)JSON5Reader.read("Infinity")).doubleValue());
		assertEquals(Double.NEGATIVE_INFINITY, ((CVMDouble)JSON5Reader.read("-Infinity")).doubleValue());
		assertEquals(true, Double.isNaN(((CVMDouble)JSON5Reader.read("NaN")).doubleValue()));

		// InputStream entry point
		assertEquals(CVMDouble.NaN, JSON5Reader.read(utf8Stream("NaN")));
		assertEquals(CVMDouble.POSITIVE_INFINITY, JSON5Reader.read(utf8Stream("Infinity")));
		assertEquals(CVMDouble.NEGATIVE_INFINITY, JSON5Reader.read(utf8Stream("-Infinity")));
	}

	private static ByteArrayInputStream utf8Stream(String s) {
		return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
	}
	
	/**
	 * Tests that are valid in JSON5, but not regular JSON
	 */
	@Test public void testJSON5Only() {
		checkJSON5Only("[1,2,]");
		checkJSON5Only("[1,2] /*bar*/");

	}

	/**
	 * JSON5 string escape sequences beyond standard JSON: \v, \xHH, line continuation,
	 * single-quoted strings, and lenient escapes (any non-special char as literal).
	 */
	@Test public void testJSON5StringEscapes() {
		// Single-quoted strings
		assertEquals(Strings.create("hello"),JSON.parseJSON5("'hello'"));
		assertEquals(Strings.create("a\"b"),JSON.parseJSON5("'a\"b'"));
		assertEquals(Strings.create("a'b"),JSON.parseJSON5("\"a'b\""));

		// \v vertical tab
		assertEquals(Strings.create("\u000B"),JSON.parseJSON5("\"\\v\""));

		// \xHH hex byte escape
		assertEquals(Strings.create(":"),JSON.parseJSON5("\"\\x3a\""));
		assertEquals(Strings.create("A"),JSON.parseJSON5("\"\\x41\""));

		// Line continuation: backslash + newline produces empty
		assertEquals(Strings.create("ab"),JSON.parseJSON5("\"a\\\nb\""));
		assertEquals(Strings.create("ab"),JSON.parseJSON5("\"a\\\r\nb\""));

		// Lenient escapes: \z is literal z (any char that's not a recognised escape)
		assertEquals(Strings.create("z"),JSON.parseJSON5("\"\\z\""));
		assertEquals(Strings.create("?"),JSON.parseJSON5("\"\\?\""));

		// \0 null character
		assertEquals(Strings.create("\0"),JSON.parseJSON5("\"\\0\""));

		// unicode escape still works
		assertEquals(Strings.create("A"),JSON.parseJSON5("\"\\u0041\""));
	}
	
	@Test
	public void testToString() {
		assertEquals("-9223372036854775809",JSON.toString(new BigInteger("-9223372036854775809")));
		assertEquals("-9223372036854775809",JSON.toString(CVMBigInteger.MIN_NEGATIVE));
		
	}
	
	/**
	 * Tests for totally invalid JSON
	 */
	@Test public void testBadJSON() {
		checkBadJSON5("[1 2]");
		checkBadJSON5("1,2");
		checkBadJSON5("{");
		checkBadJSON5("3]");
		checkBadJSON5("[,]");
		checkBadJSON5("{,}");
		
		checkBadJSON5("- Infinity"); // space between
		checkBadJSON5("Inf"); // not a JSON5 value
		checkBadJSON5("NAN"); // incorrect ccase
	}
	
	private void checkBadJSON5(String s) {
		assertThrows(ParseException.class,()->JSON.parseJSON5(s));
		assertThrows(ParseException.class,()->JSON.parse(s));
	}

	private void checkJSON5Only(String s) {
		assertThrows(ParseException.class,()->JSON.parse(s));
		JSON.parseJSON5(s);
	}
	
	@Test
	public void testJSON5NestedObjects() {
		// Nested objects — regression tests for JSON5Reader stack handling
		assertEquals(
			Maps.of(Strings.create("a"), Maps.of(Strings.create("b"), Strings.create("c"))),
			JSON.parseJSON5("{\"a\":{\"b\":\"c\"}}")
		);

		// Nested with trailing space in string value
		assertNotNull(JSON.parseJSON5("{\"x\":{\"y\":\"hello \"}}"));

		// Multiple keys in inner object
		assertNotNull(JSON.parseJSON5("{\"op\":\"test\",\"input\":{\"a\":\"1\",\"b\":\"2\"}}"));

		// Triple nesting
		assertNotNull(JSON.parseJSON5("{\"a\":{\"b\":{\"c\":\"d\"}}}"));

		// Realistic invoke payload (the pattern that triggered NoSuchElementException in production)
		String invoke = "{\"operation\":\"jvm:stringConcat\",\"input\":{\"first\":\"Hello \",\"second\":\"World!\"}}";
		ACell result = JSON.parseJSON5(invoke);
		assertNotNull(result);
	}

	/**
	 * Malformed JSON must always throw ParseException, never NoSuchElementException
	 * or other internal errors. The JSON5Reader uses a stack-based listener and
	 * ANTLR error recovery can cause unbalanced enter/exit events.
	 */
	@Test
	public void testMalformedJSONNeverThrowsInternalError() {
		String[] malformed = {
			// Extra closing braces — could cause extra exitObj without matching enterObj
			"{\"a\":\"b\"}}",
			"{}}}",
			"{\"a\":{\"b\":\"c\"}}}",
			"[1,2]]}",
			// Mismatched braces/brackets
			"{\"a\":[}",
			"[{]",
			"{]",
			"[}",
			// Truncated nested objects
			"{\"a\":{\"b\":",
			"{\"a\":{",
			"{\"a\":[1,",
			// Random garbage mixed with structure
			"}{",
			"][",
			"}}{{",
			"]][[",
			// Empty and whitespace-only
			"",
			"   ",
			// Lone delimiters
			"}",
			"]",
			":",
			",",
			// Deeply nested unclosed
			"{{{",
			"[[[",
			"{\"a\":{\"b\":{\"c\":",
		};

		for (String input : malformed) {
			try {
				JSON.parseJSON5(input);
				// If it parses without error, that's fine (some of these might be valid edge cases)
			} catch (ParseException e) {
				// Expected — malformed input should throw ParseException
			} catch (Exception e) {
				fail("parseJSON5(\"" + input + "\") threw " + e.getClass().getSimpleName()
					+ " instead of ParseException: " + e.getMessage());
			}
		}
	}

	@Test
	public void testJSON5Example() throws IOException {
		String json5=Utils.readString(Utils.getResourceAsStream("/utils/test.json5"));
		
		ACell cj=JSON.parseJSON5(json5);
		assertNotNull(cj);
		
	}
	
	@Test
	public void testJSONRoundTrips() {
		
		doJSONRoundTrip(1L,CVMLong.ONE);
		doJSONRoundTrip(1.0,CVMDouble.ONE);
		doJSONRoundTrip(null,null);
		
		doJSONRoundTrip(new ArrayList<Object>(),Vectors.empty());
		doJSONRoundTrip(List.of(1,2),Vectors.of(1,2));
		doJSONRoundTrip("hello",Strings.create("hello"));
		doJSONRoundTrip(new BigInteger("-9223372036854775809"),CVMBigInteger.MIN_NEGATIVE);
		doJSONRoundTrip("",Strings.EMPTY);
		doJSONRoundTrip(true,CVMBool.TRUE);
		
		doJSONRoundTrip(new HashMap<String,Object>(),Maps.empty());
		doJSONRoundTrip(Maps.hashMapOf("1",2,"3",4),Maps.of("1",2,"3",4));
		doJSONRoundTrip(Maps.hashMapOf("1",2,"\"3\n\"",Maps.hashMapOf("4",5)),Maps.of("1",2,"\"3\n\"",Maps.of("4",5)));
	}
	

	private void doJSONRoundTrip(Object o, ACell c) {
		// o should convert to c
		assertEquals(c,RT.cvm(o)); 
		
		// c should round trip via JSON back to c, since JSON is a subset of CVM types
		ACell roundTrip=RT.cvm(JSON.json(c));
		assertEquals(c,roundTrip); 
		
		// c should also round trip via JVM equivalent, since we are using JSON subset
		ACell roundTrip2=RT.cvm((Object) RT.jvm(c));
		assertEquals(c,roundTrip2); 
		
		String js1=JSON.toString(o);
		String js2=JSON.toString(c);
		assertEquals(js1.length(),js2.length()); // should be same length, orders might differ

		String jsp=JSON.printPretty(c).toString();

		// Written JSON should be parseable as strict JSON
		assertEquals(c,JSON.parse(js1),()->"JSON="+js1);
		assertEquals(c,JSON.parse(js2));
		assertEquals(c,JSON.parse(jsp));
		assertEquals(c,JSONReader.read(js2));

		// Should also be valid JSON5
		assertEquals(c,JSON.parseJSON5(js2)); 
	}
	
	@Test
	public void testCVMValues() {	
		// Any CVM value should work here (but might not round-trip)
		doCVMJSONTest(CVMLong.MAX_VALUE);
		doCVMJSONTest(CVMDouble.NEGATIVE_INFINITY);
		doCVMJSONTest(CVMBigInteger.MIN_NEGATIVE);
		doCVMJSONTest(null);
		doCVMJSONTest(CVMBool.TRUE);
		doCVMJSONTest(Blob.EMPTY);
		doCVMJSONTest(Blob.SINGLE_ONE);
		doCVMJSONTest(Maps.of(1,2,3,4));
		doCVMJSONTest(Symbols.FOO);
		doCVMJSONTest(Keywords.BAR);
		doCVMJSONTest(CVMChar.MAX_VALUE);
		doCVMJSONTest(CVMChar.create('"'));
		doCVMJSONTest(Vectors.of(1,Strings.COLON,Maps.of(null,Sets.empty())));
	}

	
	private void doCVMJSONTest(ACell c) {
		Object json=JSON.json(c);
		Object jvm=JSON.jvm(JSON.printPretty(c));
		Object jvm2=JSON.jvm(JSON.print(c));
		
		ACell c2=RT.cvm(json);
		assertEquals(c2,RT.cvm(jvm));
		assertEquals(c2,RT.cvm(jvm2));
		
		doJSONRoundTrip(json,c2);
	}
	
}
