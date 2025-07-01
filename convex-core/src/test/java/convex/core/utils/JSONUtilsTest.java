package convex.core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.Test;

import convex.core.ErrorCodes;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.Symbols;
import convex.core.data.ACell;
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
import convex.core.json.JSONReader;
import convex.core.lang.RT;
import convex.core.util.JSONUtils;

public class JSONUtilsTest {

	@Test public void testPrint() {
		assertEquals("null",JSONUtils.toString(null));
		
		assertEquals("[]",JSONUtils.toString(Vectors.empty()));
		assertEquals("[]",JSONUtils.toString(Sets.empty()));
		assertEquals("{}",JSONUtils.toString(Maps.empty()));
	
		assertEquals("\"nil\"",JSONUtils.toString("nil"));

		assertEquals("[1,2,null]",JSONUtils.toString(Vectors.of(1,2,null)));

		assertEquals("true",JSONUtils.toString(true));
		assertEquals("false",JSONUtils.toString(false));
		assertEquals("true",JSONUtils.toString(CVMBool.TRUE));
		assertEquals("false",JSONUtils.toString(CVMBool.FALSE));
		
		assertEquals("\"\\n\"",JSONUtils.toString("\n"));
		assertEquals("\" \\\" \"",JSONUtils.toString(" \" "));
		
		assertEquals("\"foo\"",JSONUtils.toString(Symbols.FOO));
		assertEquals("\"foo\"",JSONUtils.toString(Keywords.FOO));
		assertEquals("\"CAST\"",JSONUtils.toString(ErrorCodes.CAST));

	}

	@Test
	public void parseStrict() {
		assertNull(JSONUtils.parse("  null  "));
		assertEquals(CVMBool.TRUE,JSONUtils.parseJSON5("true"));
		assertEquals(CVMLong.ONE,JSONUtils.parse("1"));
		assertEquals(Strings.NULL,JSONUtils.parse("\"null\""));

		assertEquals(Vectors.of(1,Maps.empty()),JSONUtils.parseJSON5("[1,{}]"));

		
	}

	@Test
	public void testParseJSON5() {
		assertNull(JSONUtils.parseJSON5("null"));
		assertEquals(CVMBool.TRUE,JSONUtils.parseJSON5("true"));
		assertEquals(CVMBool.FALSE,JSONUtils.parseJSON5("   false  "));
		
		assertSame(Vectors.empty(),JSONUtils.parseJSON5("[]"));
		assertEquals(Vectors.of(true,null),JSONUtils.parseJSON5("[true,null]"));
		assertEquals(Vectors.of(1,2),JSONUtils.parseJSON5("[1,2]"));

		assertEquals(CVMLong.MAX_VALUE,JSONUtils.parseJSON5("9223372036854775807"));
		assertEquals(CVMBigInteger.MIN_POSITIVE,JSONUtils.parseJSON5("9223372036854775808"));

		
		assertSame(CVMLong.ONE,JSONUtils.parseJSON5("1"));
		assertEquals(CVMDouble.ONE,JSONUtils.parseJSON5("1.0"));
		assertEquals(CVMDouble.create(-200.0),JSONUtils.parseJSON5("-.2e3"));

		assertEquals(Strings.NIL,JSONUtils.parseJSON5("\"nil\""));

		assertSame(Maps.empty(),JSONUtils.parseJSON5("{}"));
		assertSame(Maps.empty(),JSONUtils.parseJSON5("{ /* foo */ } /*bar*/ /*baz*/"));
		assertEquals(Maps.of(Strings.NIL,1),JSONUtils.parseJSON5("{\"nil\": 1}"));
		assertEquals(Maps.of(Strings.NIL,1),JSONUtils.parseJSON5("{nil: 1}"));
		assertEquals(Maps.of(Strings.EMPTY,Vectors.empty()),JSONUtils.parseJSON5("{\"\": []}"));
	
		// Some errors
		assertThrows(Exception.class,()->JSONUtils.parseJSON5("{foo:bar}")); // TODO: Why not ParseException?
		
		// Trailing commas allowed
		assertEquals(JSONUtils.parseJSON5("[3]"),JSONUtils.parseJSON5("[3,]"));
		assertEquals(JSONUtils.parseJSON5("[1, 3]"),JSONUtils.parseJSON5("[1,3, ]"));
		assertEquals(JSONUtils.parseJSON5("{\"foo\":1}"),JSONUtils.parseJSON5("{\"foo\":1,}"));


		// Special cases
		assertEquals(Strings.create("a\"b"),JSONUtils.parseJSON5("\"a\\\"b\""));

	}
	
	@Test
	public void testEscape() {
		assertEquals("\\n",JSONUtils.escape("\n").toString());
		assertEquals(StringShort.create(" \\\""),JSONUtils.escape(" \""));
	}
	
	@Test
	public void testJSON() {
		assertNull(JSONUtils.json(null));
		
		assertEquals((Long)13L,JSONUtils.json(Address.create(13)));
		assertEquals("0xcafebabe",JSONUtils.json(Blob.fromHex("cafebabe")));
		assertEquals("0x",JSONUtils.json(Blobs.empty()));
		assertEquals("{}",JSONUtils.json(Index.none()).toString());
		assertEquals("{}",JSONUtils.json(Maps.empty()).toString());
		assertEquals("[1, 2]",JSONUtils.json(Vectors.of(1,2)).toString());
		assertEquals("[1, 2]",JSONUtils.json(Lists.of(1,2)).toString());
		assertEquals("c",JSONUtils.json(CVMChar.create('c')));

		assertEquals("foo",JSONUtils.json(Symbols.FOO));
		assertEquals(":foo",JSONUtils.json(Keywords.FOO));
		
		// Note keywords get colon removed when used as JSON key
		assertEquals(":bar",JSONUtils.jsonMap(Maps.of(Keywords.FOO, Keywords.BAR)).get("foo"));

		
		// JSON should convert keys to strings
		assertEquals(Maps.of("1",2), RT.cvm(JSONUtils.json(Maps.of(1,2))));
		assertEquals(Maps.of("[]",3), RT.cvm(JSONUtils.json(Maps.of(Vectors.empty(),3))));
		assertEquals(Maps.of("[\"\" 3]",4), RT.cvm(JSONUtils.json(Maps.of(Vectors.of("",3),4))));
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
	
	@Test
	public void testJSONComments() {
		assertEquals(Vectors.of(true,null),JSONUtils.parseJSON5("[true, /* \n */ null]"));
		assertEquals(RT.cvm(12),JSONUtils.parseJSON5("12 //foo"));
		assertEquals(RT.cvm(12),JSONUtils.parseJSON5("//foo \n 12"));
		assertEquals(RT.cvm(12),JSONUtils.parseJSON5("//foo /* \n 12"));

		assertThrows(ParseException.class,()->JSONUtils.parseJSON5("/* 67")); // comment not closed
		assertThrows(ParseException.class,()->JSONUtils.parseJSON5("//")); // no value
		
	}
	
	@Test
	public void testJSONDoubles() {
		assertEquals(CVMDouble.POSITIVE_INFINITY,JSONUtils.parseJSON5("Infinity"));
		assertEquals(CVMDouble.POSITIVE_INFINITY,JSONUtils.parseJSON5("+Infinity"));
		assertEquals(CVMDouble.NEGATIVE_INFINITY,JSONUtils.parseJSON5("-Infinity"));
		assertEquals(CVMDouble.NEGATIVE_INFINITY,JSONUtils.parseJSON5(" -Infinity"));
		assertEquals(CVMDouble.NaN,JSONUtils.parseJSON5(" NaN"));

		
	}
	
	/**
	 * Tests that are valid in JSON5, but not regular JSON
	 */
	@Test public void testJSON5Only() {
		checkJSON5Only("[1,2,]");
		checkJSON5Only("[1,2] /*bar*/");

	}
	
	@Test
	public void testToString() {
		assertEquals("-9223372036854775809",JSONUtils.toString(new BigInteger("-9223372036854775809")));
		assertEquals("-9223372036854775809",JSONUtils.toString(CVMBigInteger.MIN_NEGATIVE));
		
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
		assertThrows(ParseException.class,()->JSONUtils.parseJSON5(s));
		assertThrows(ParseException.class,()->JSONUtils.parse(s));
	}

	private void checkJSON5Only(String s) {
		assertThrows(ParseException.class,()->JSONUtils.parse(s));
		JSONUtils.parseJSON5(s);
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
	}

	private void doJSONRoundTrip(Object o, ACell c) {
		// o should convert to c
		assertEquals(c,RT.cvm(o)); 
		
		// c should round trip via JSON back to c, since JSON is a subset of CVM types
		ACell roundTrip=RT.cvm(JSONUtils.json(c));
		assertEquals(c,roundTrip); 
		
		// c should also round trip via JVM equivalent, since we are using JSON subset
		ACell roundTrip2=RT.cvm((Object) RT.jvm(c));
		assertEquals(c,roundTrip2); 
		
		String js1=JSONUtils.toString(o);
		String js2=JSONUtils.toString(c);
		assertEquals(js1.length(),js2.length()); // should be same length, orders might differ
		
		// Written JSON should be parseable as strict JSON
		assertEquals(c,JSONUtils.parse(js1),()->"JSON="+js1);
		assertEquals(c,JSONUtils.parse(js2));
		assertEquals(c,JSONReader.read(js2));
	}
	
}
