package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.lang.RT;
import convex.core.lang.Symbols;
import convex.test.Samples;

public class StringsTest {

	
	@Test public void testStringShort() {
		doShortStringTest("");
		doShortStringTest("Test");
		doShortStringTest("abcdefghijklmnopqrstuvwxyz");
		doShortStringTest("\u1234"); // ethiopic syllable 'see', apparently
		doShortStringTest(Samples.IPSUM);
		doShortStringTest(Samples.SPANISH);
		doShortStringTest(Samples.RUSSIAN);
	}
	
	@Test public void testExamples() {
		doStringTest(Strings.EMPTY);
		doStringTest(Samples.MAX_EMBEDDED_STRING);
		doStringTest(Samples.NON_EMBEDDED_STRING);
		doStringTest(Samples.MAX_SHORT_STRING);
		doStringTest(Samples.MIN_TREE_STRING);
	}
	
	@Test public void testEmbedding() {
		assertEquals(Format.MAX_EMBEDDED_LENGTH,Samples.MAX_EMBEDDED_STRING.getEncodingLength());
		assertTrue(Samples.MAX_EMBEDDED_STRING.isEmbedded());
		assertEquals(Format.MAX_EMBEDDED_LENGTH+1,Samples.NON_EMBEDDED_STRING.getEncodingLength());
		assertFalse(Samples.NON_EMBEDDED_STRING.isEmbedded());
	}
	
	public void doShortStringTest(String t) {
		StringShort ss=StringShort.create(t);
		String t2=ss.toString();
		assertEquals(t,t2);
		
		assertEquals(t.length(),ss.toString().length());
		assertEquals(t,ss.toString());
		
		doStringTest(ss);
	}
	
	@Test public void testStringTree() {
		String src="0123456789abcdef";
		for (int i=0; i<8; i++) {
			src=src+src;
		}
		AString chunk=Strings.create(src);
		AString twoChunk=Strings.create(src+src);
		
		assertEquals('2',(char)twoChunk.byteAt(4098));
		
		doStringTest(chunk);
		doStringTest(twoChunk);
		
		// Span across 
		AString span=twoChunk.subSequence(4000, 4200);
		doStringTest(span);
	}
	
	@Test public void testEmptyString() {
		StringShort s=Strings.EMPTY;
		doStringTest(s);
	}
	
	@Test public void testRTStr() {
		assertEquals("foo",RT.toString(Symbols.FOO));
		assertEquals(":foo",RT.toString(Keywords.FOO));
	}
	
	public void doStringTest(AString a) {
		long n=a.count();
		assertEquals(Strings.EXCESS_BYTE,a.byteAt(-1));
		assertEquals(Strings.EXCESS_BYTE,a.byteAt(n));
		
		// Round trip to Java String
		String js=a.toString();
		AString b=Strings.create(js);
		assertEquals(a.count(),b.count());
		assertEquals(a,b);
		
		// Round Trip to Blob
		ABlob bs=a.toBlob();
		AString abs=Strings.create(bs);
		assertEquals(a,abs);
		
		ObjectsTest.doAnyValueTests(a);
	}
}
