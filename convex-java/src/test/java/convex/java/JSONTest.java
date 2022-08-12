package convex.java;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashMap;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Lists;
import convex.core.data.Maps;
import convex.core.lang.RT;

public class JSONTest {

	@Test 
	public void testJSONParse() {
		assertEquals((Long)1L,JSON.parse("1"));
		assertEquals((Double)1.0,JSON.parse("1.0"));
		assertEquals(false,JSON.parse("false"));
	}
	
	@Test 
	public void testPrint() {
		assertEquals("1",JSON.toString(1L));
		assertEquals("1.0",JSON.toString(1.0));
		assertEquals("false",JSON.toString(false));
		assertEquals("[]",JSON.toString(Lists.empty()));
		assertEquals("{}",JSON.toString(new HashMap<>()));
		assertEquals("null",JSON.toString(null));
		assertEquals("{\"foo\":1}",JSON.toString(Maps.hashMapOf("foo",1)));
	}
	
	@Test 
	public void testJSONTrips() {
		doJSONTest(1L,"1");
		doJSONTest(1.0,"1.0");
		doJSONTest(new ArrayList<>(),"[]");
		doJSONTest(new HashMap<>(),"{}");
		doJSONTest(true,"true");
		doJSONTest(null,"null");
		
		// hairy escaping stuff
		doJSONTest("\\","\"\\\\\"");
		doJSONTest("\"","\"\\\"\"");
		
		doJSONTest("{\"foo\": 1, \"bar\":[true 1.0 13]}");
		doJSONTest("[1 2 \"foo\" null]");
	}
	
	private void doJSONTest(String s) {
		Object o=JSON.parse(s);
		doJSONTest(o,s);
	}

	private void doJSONTest(Object v, String s) {
		Object o=JSON.parse(s);
		ACell c=RT.cvm(v); // c is CVM representation of JSON Object
		assertEquals(c,RT.cvm(o));
		assertEquals(c,RT.cvm(RT.json(c)));
		assertEquals(c,RT.cvm(JSON.parse(JSON.toString(v))));
		assertEquals(c,RT.cvm(JSON.parse(JSON.toPrettyString(v))));
	}
}
