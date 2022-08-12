package convex.java;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashMap;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.lang.RT;

public class JSONTest {

	@Test 
	public void testJSONParse() {
		assertEquals((Long)1L,JSON.parse("1"));
		assertEquals((Double)1.0,JSON.parse("1.0"));
	}
	
	@Test 
	public void testJSONTrips() {
		doJSONTest(1L,"1");
		doJSONTest(1.0,"1.0");
		doJSONTest(new ArrayList<>(),"[]");
		doJSONTest(new HashMap<>(),"{}");
		doJSONTest(true,"true");
		doJSONTest(null,"null");
		
		doJSONTest("{\"foo\": 1, \"bar\":[true 1.0 13]}");
		doJSONTest("[1 2 \"foo\"]");
	}
	
	private void doJSONTest(String s) {
		Object o=JSON.parse(s);
		doJSONTest(o,s);
	}

	private void doJSONTest(Object v, String s) {
		Object o=JSON.parse(s);
		ACell c=RT.cvm(v);
		assertEquals(c,RT.cvm(o));
		assertEquals(c,RT.cvm(RT.json(c)));
		assertEquals(c,RT.cvm(JSON.parse(JSON.toString(v))));
		assertEquals(c,RT.cvm(JSON.parse(JSON.toPrettyString(v))));
	}
}
