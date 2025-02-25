package convex.core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.Test;

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
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMChar;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSONUtils;

public class JSONUtilsTest {

	@Test public void testPrint() {
		assertEquals("null",JSONUtils.toString(null));
		
		assertEquals("[]",JSONUtils.toString(Vectors.empty()));
		assertEquals("[]",JSONUtils.toString(Sets.empty()));
		assertEquals("{}",JSONUtils.toString(Maps.empty()));
	
		assertEquals("\"nil\"",JSONUtils.toString("nil"));

		assertEquals("[1 2 null]",JSONUtils.toString(Vectors.of(1,2,null)));

		assertEquals("true",JSONUtils.toString(true));
		assertEquals("false",JSONUtils.toString(false));
		assertEquals("true",JSONUtils.toString(CVMBool.TRUE));
		assertEquals("false",JSONUtils.toString(CVMBool.FALSE));
		
		
		assertEquals("\"foo\"",JSONUtils.toString(Symbols.FOO));
		assertEquals("\"foo\"",JSONUtils.toString(Keywords.FOO));

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
	public void testJSONRoundTrips() {
		
		doJSONRoundTrip(1L,CVMLong.ONE);
		doJSONRoundTrip(1.0,CVMDouble.ONE);
		doJSONRoundTrip(null,null);
		
		doJSONRoundTrip(new ArrayList<Object>(),Vectors.empty());
		doJSONRoundTrip(List.of(1,2),Vectors.of(1,2));
		doJSONRoundTrip("hello",Strings.create("hello"));
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
		ACell roundTrip2=RT.cvm(RT.jvm(c));
		assertEquals(c,roundTrip2); 
		
		String js1=JSONUtils.toString(o);
		String js2=JSONUtils.toString(c);
		assertEquals(js1.length(),js2.length()); // should be same length, orders might differ
	}
	
}
