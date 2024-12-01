package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.Constants;
import convex.core.cvm.Keywords;
import convex.core.cvm.Symbols;
import convex.core.data.prim.CVMChar;
import convex.core.data.util.BlobBuilder;
import convex.core.lang.RT;
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
		doStringTest(StringShort.EMPTY);
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
	
	@Test public void testHexString() {
		assertEquals("20",Strings.create(" ").toHexString());
		assertEquals("2",Strings.create(" ").toHexString(1));
	}
	
	@Test public void testStringTree() {
		String src="0123456789abcdef";
		for (int i=0; i<8; i++) {
			src=src+src;
		}
		AString chunk=Strings.create(src);
		AString twoChunk=Strings.create(src+src);
		
		assertEquals('2',(char)twoChunk.byteAt(4098));
		
		// intAt should span chunks, pick up ascii
		int spanInt=twoChunk.intAt(4096-2);
		assertEquals(Strings.create("cdef0123").intAt(2),spanInt);
		assertEquals(0x65663031,spanInt); // ASCII fo "ef01"
		
		doStringTest(chunk);
		doStringTest(twoChunk);
		
		// Span across 
		AString span=twoChunk.slice(4000, 4200);
		assertEquals(200,span.count());
		doStringTest(span);
	}
	
	@Test public void testStringSplit() {
		assertEquals(Vectors.of("","abc"),Strings.create(":abc").split(CVMChar.create(':')));
		assertEquals(Vectors.of(""),Strings.create("").split(CVMChar.create(':')));
		assertEquals(Vectors.of("","a"),Strings.create(":a").split(CVMChar.create(':')));
		assertEquals(Vectors.of("foo","bar"),Strings.create("foo@bar").split(CVMChar.create('@')));
		assertEquals(Vectors.of("","","",""),Strings.create("|||").split(CVMChar.create('|')));
		assertEquals(Vectors.of("|||"),Strings.create("|||").split(CVMChar.create(':')));
	}
	
	@Test public void testCompare() {
		assertStringOrder("a","b");
		assertStringOrder("abc","abd","ab\u1234"); // Bigger unicode code point comes last
		assertStringOrder("a","abc");
		assertStringOrder("","a","aaaaaaa");
		
		assertStringOrder("aaaaaaaa","aaaaaaab","aaaaaaba","baaaaaaa","baaaaaab"); // Baa Baa Bowyer, Have you any wool?
	}
	
	private void assertStringOrder(String... ss) {
		int n=ss.length;
		AString[] strs=new AString[n];
		for (int i=0; i<n; i++) {
			strs[i]=Strings.create(ss[i]);
		}
		for (int i=0; (i+1)<n; i++) {
			assertTrue(strs[i].compareTo(strs[i+1].toBlob())<0);
		}
	}

	@Test public void testStringJoin() {
		splitJoinRoundTrip("a:b:c", ':',3);
		splitJoinRoundTrip("a-b-c", ':',1);
		splitJoinRoundTrip("\u1234", ':',1);
		splitJoinRoundTrip("\u1234:\u1235", ':',2);
		splitJoinRoundTrip("\u1234\u1235", '\u1234',2);
		splitJoinRoundTrip(Samples.RUSSIAN, ' ',10);
		
		BlobBuilder bb=new BlobBuilder();
		AString chunk=Strings.create("abcdefghi-abcdefghi");
		for (int i=0; i<800; i++) {
			bb.append(chunk);
		}
		splitJoinRoundTrip(bb.getCVMString().toString(), '-',801);
	}
	
	private void splitJoinRoundTrip(String s, char sep, int expectedCount) {
		AString st=Strings.create(s);
		CVMChar ch=CVMChar.create(sep);
		AVector<AString> ss=st.split(ch);
		AString jn=Strings.join(ss, ch);
		assertEquals(st,jn);
		assertEquals(expectedCount,ss.count());
	}

	@Test public void testEmptyString() {
		StringShort s=StringShort.EMPTY;
		doStringTest(s);
	}
	
	@Test public void testEmbeddedString() {
		StringShort s=Samples.MAX_EMBEDDED_STRING;
		assertTrue(s.isEmbedded());
		assertTrue(s.getRef().isDirect());
		
		Blob b=s.toBlob();
		
		assertEquals(b.toHexString(),s.toHexString());
		
		Blob enc=s.getEncoding();
		assertEquals(s.getTag(),enc.byteAt(0));
	}
	
	@Test public void testRTStr() {
		assertEquals("foo",RT.toString(Symbols.FOO));
		assertEquals(":foo",RT.toString(Keywords.FOO));
	}
	
	@Test public void testRTPrint() {
		AString s=Strings.create("\n");
		assertEquals("\"\\n\"",RT.print(s, 100).toString());
		
		assertEquals(Strings.NIL,RT.print(null,3));
		assertEquals(null,RT.print(null,1));
	}
	
	@Test 
	public void testPrint() {
		assertEquals("\"\"",Strings.empty().print(2).toString());
		assertEquals("\"foo bar\"",Strings.create("foo bar").print().toString());
	}
	
	@Test public void testPrintExceeded() {
		AString s=Strings.create("foobar");
		String exp="\"f"+Constants.PRINT_EXCEEDED_MESSAGE;
		assertEquals(exp,s.print(2).toString());
		
		assertEquals(Constants.PRINT_EXCEEDED_MESSAGE,s.print(0));
	}
	
	@Test public void testIntAt() {
		AString s= Strings.create(Blob.fromHex("12345678abcd"));
		assertEquals(0x12345678,s.intAt(0));
		assertEquals(0x345678ab,s.intAt(1));
		assertEquals(0x5678abcd,s.intAt(2));
		assertEquals(0xabcdffff,s.intAt(4));
		assertEquals(0xffffffff,s.intAt(6)); // 0xff beyond end of string
		assertEquals(0xffffffff,s.intAt(-6)); // 0xff before start of string
	}
	
	@Test public void testCharAt() {
		testCharAt("ab",1,'b');
		testCharAt("ab",2,null);
		testCharAt("\u1234\u1235", 0,'\u1234');
		testCharAt("", 0,null);

		testCharAt(Strings.fromHex("65ff65"),1,null);
	}
	
	private void testCharAt(String string, int i, Character c) {
		AString s= Strings.create(string);
		testCharAt(s,i,c);
	}
		
	private void testCharAt(AString s, int i, Character c) {
		int cp=s.charAt(i);
		CVMChar cg=s.get(i);
		assertEquals(cg,CVMChar.create(cp));
		if (c!=null) assertEquals(CVMChar.create(c),cg);
	}

	public void doStringTest(AString a) {
		long n=a.count();
		assertEquals(Strings.EXCESS_BYTE,a.byteAt(-1));
		assertEquals(Strings.EXCESS_BYTE,a.byteAt(n));
		
		// get should return null for invalid positions
		assertNull(a.get(-1));
		assertNull(a.get(n));
		
		// Round trip to Java String
		String js=a.toString();
		AString b=Strings.create(js);
		assertEquals(a.count(),b.count());
		assertEquals(a,b);
		assertEquals(0,a.compareTo(b.toBlob()));
		
		// Round Trip to Blob
		ABlob bs=a.toBlob();
		AString abs=Strings.create(bs);
		assertEquals(a,abs);
		
		// fall back to bloblike tests
		BlobsTest.doBlobLikeTests(a);
	}
}
