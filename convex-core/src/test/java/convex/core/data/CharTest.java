package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.CVMChar;
import convex.core.lang.RT;
import convex.core.lang.Reader;

public class CharTest {
	@Test public void testASCIIChars() {
		for (int i=0; i<128; i++) {
			CVMChar c=CVMChar.create(i);
			assertEquals(i,c.longValue());
			
			assertSame(c,CVMChar.create(i)); // check it comes from cache
			
			doCharTests(c);
		}
	}
	
	@Test public void testLongUTF() {
		int cp=CVMChar.codepointFromUTFInt(0xf0928080); // SUMERIAN CUNEIFORM SIGN A
		assertEquals(73728,cp);
		CVMChar c=CVMChar.create(cp);
		String js=c.toString();
		assertEquals(2,js.length()); // should encode to two UTF-16 chars
		assertEquals(0xD808,js.charAt(0)); // first UTF-16 char
		assertEquals(0xDC00,js.charAt(1)); // second UTF-16 char
		
		AString s=Strings.create(js);
		assertEquals(4,s.count());
		assertEquals(c,s.get(0));
		
		AString p=RT.print(c);
		assertEquals(5,p.count()); // UTF 4 bytes plus leading '/'
		assertEquals(c,p.get(1));
		
		String pjs=p.toString();
		assertEquals(3,pjs.length()); // should encode to '/' plus two UTF-16 chars
		
		// TODO: Reader support for multi-byte chars
		//assertEquals(c,Reader.read(pjs));
	}
	
	@Test public void testEquality() {
		ObjectsTest.doEqualityTests(CVMChar.create(999), CVMChar.create(999));
	}
	
	@Test public void testUTF16Chars() {
		testUTF(0);
		testUTF(128);
		testUTF(255);
		testUTF(256);
		testUTF(0x014a); // LATIN CAPITAL LETTER ENG (U+014A)
		testUTF(0x0680); // ARABIC LETTER BEHEH (U+0680)
		testUTF(0x0D60);// MALAYALAM LETTER VOCALIC RR (U+0D60)
		
		//testUTF(CVMChar.codepointFromUTFInt(0xf0928080)); // SUMERIAN CUNEIFORM CHAR
	}
	
	private void testUTF(long i) {
		CVMChar c=CVMChar.create(i);
		doCharTests(c);
	}

	/**
	 * Test for and valid CVMChar
	 * @param c Character value to test
	 */
	public void doCharTests(CVMChar c) {
		// Should round trip as a readable character
		assertEquals(c,Reader.read(RT.print(c).toString()));
		
		// Should round trip when contained in a String
		AString s=Strings.create(c.toString());
		assertEquals(s,Reader.read(RT.print(s).toString()));
		
		ObjectsTest.doAnyValueTests(c);
	}
}
