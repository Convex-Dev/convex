package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.Constants;
import convex.core.ErrorCodes;
import convex.core.cvm.Keywords;
import convex.core.cvm.Symbols;
import convex.core.data.prim.CVMChar;
import convex.core.data.util.BlobBuilder;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.test.Samples;

public class StringsTest {

	@Test
	public void testStringShort() {
		doShortStringTest("");
		doShortStringTest("Test");
		doShortStringTest("abcdefghijklmnopqrstuvwxyz");
		doShortStringTest("\u1234"); // ethiopic syllable 'see', apparently
		doShortStringTest(Samples.IPSUM);
		doShortStringTest(Samples.SPANISH);
		doShortStringTest(Samples.RUSSIAN);
		doShortStringTest("\"\n\r\t\\"); // some escape codes

	}

	@Test
	public void testExamples() {
		doStringTest(StringShort.EMPTY);
		doStringTest(Samples.MAX_EMBEDDED_STRING);
		doStringTest(Samples.NON_EMBEDDED_STRING);
		doStringTest(Samples.MAX_SHORT_STRING);
		doStringTest(Samples.MIN_TREE_STRING);
	}

	@Test
	public void testEmbedding() {
		assertEquals(Format.MAX_EMBEDDED_LENGTH, Samples.MAX_EMBEDDED_STRING.getEncodingLength());
		assertTrue(Samples.MAX_EMBEDDED_STRING.isEmbedded());
		assertEquals(Format.MAX_EMBEDDED_LENGTH + 1, Samples.NON_EMBEDDED_STRING.getEncodingLength());
		assertFalse(Samples.NON_EMBEDDED_STRING.isEmbedded());
	}

	public void doShortStringTest(String t) {
		StringShort ss = StringShort.create(t);
		String t2 = ss.toString();
		assertEquals(t, t2);

		assertEquals(t.length(), ss.toString().length());
		assertEquals(t, ss.toString());

		doStringTest(ss);
	}

	@Test
	public void testHexString() {
		assertEquals("20", Strings.create(" ").toHexString());
		assertEquals("2", Strings.create(" ").toHexString(1));
	}

	@Test
	public void testStringTree() throws IOException {
		String src = "0123456789abcdef";
		for (int i = 0; i < 8; i++) {
			src = src + src;
		}
		AString chunk = Strings.create(src);
		AString twoChunk = Strings.create(src + src);

		assertEquals('2', (char) twoChunk.byteAt(4098));

		// intAt should span chunks, pick up ascii
		int spanInt = twoChunk.intAt(4096 - 2);
		assertEquals(Strings.create("cdef0123").intAt(2), spanInt);
		assertEquals(0x65663031, spanInt); // ASCII fo "ef01"

		doStringTest(chunk);
		doStringTest(twoChunk);

		// Span across
		AString span = twoChunk.slice(4000, 4200);
		assertEquals(200, span.count());
		doStringTest(span);
		
		assertEquals(twoChunk,Strings.create(Blobs.fromStream(twoChunk.getInputStream())));
	}

	@Test
	public void testStringSplit() {
		assertEquals(Vectors.of("", "abc"), Strings.create(":abc").split(CVMChar.create(':')));
		assertEquals(Vectors.of(""), Strings.create("").split(CVMChar.create(':')));
		assertEquals(Vectors.of("", "a"), Strings.create(":a").split(CVMChar.create(':')));
		assertEquals(Vectors.of("foo", "bar"), Strings.create("foo@bar").split(CVMChar.create('@')));
		assertEquals(Vectors.of("", "", "", ""), Strings.create("|||").split(CVMChar.create('|')));
		assertEquals(Vectors.of("|||"), Strings.create("|||").split(CVMChar.create(':')));
	}

	@Test
	public void testCompare() {
		assertStringOrder("a", "b");
		assertStringOrder("abc", "abd", "ab\u1234"); // Bigger unicode code point comes last
		assertStringOrder("a", "abc");
		assertStringOrder("", "a", "aaaaaaa");

		// Baa Baa Bowyer, Have you any wool?
		assertStringOrder("aaaaaaaa", "aaaaaaab", "aaaaaaba", "baaaaaaa", "baaaaaab"); 
	}

	private void assertStringOrder(String... ss) {
		int n = ss.length;
		AString[] strs = new AString[n];
		for (int i = 0; i < n; i++) {
			strs[i] = Strings.create(ss[i]);
		}
		for (int i = 0; (i + 1) < n; i++) {
			assertTrue(strs[i].compareTo(strs[i + 1].toBlob()) < 0);
		}
	}

	@Test
	public void testStringJoin() {
		splitJoinRoundTrip("a:b:c", ':', 3);
		splitJoinRoundTrip("a-b-c", ':', 1);
		splitJoinRoundTrip("\u1234", ':', 1);
		splitJoinRoundTrip("\u1234:\u1235", ':', 2);
		splitJoinRoundTrip("\u1234\u1235", '\u1234', 2);
		splitJoinRoundTrip(Samples.RUSSIAN, ' ', 10);

		BlobBuilder bb = new BlobBuilder();
		AString chunk = Strings.create("abcdefghi-abcdefghi");
		for (int i = 0; i < 800; i++) {
			bb.append(chunk);
		}
		splitJoinRoundTrip(bb.getCVMString().toString(), '-', 801);
	}

	private void splitJoinRoundTrip(String s, char sep, int expectedCount) {
		AString st = Strings.create(s);
		CVMChar ch = CVMChar.create(sep);
		AVector<AString> ss = st.split(ch);
		AString jn = Strings.join(ss, ch);
		assertEquals(st, jn);
		assertEquals(expectedCount, ss.count());
	}

	@Test
	public void testEmptyString() {
		StringShort s = StringShort.EMPTY;
		assertEquals(0,s.count());
		doStringTest(s);
	}

	@Test
	public void testEmbeddedString() {
		StringShort s = Samples.MAX_EMBEDDED_STRING;
		assertTrue(s.isEmbedded());
		assertTrue(s.getRef().isDirect());

		Blob b = s.toBlob();

		assertEquals(b.toHexString(), s.toHexString());

		Blob enc = s.getEncoding();
		assertEquals(s.getTag(), enc.byteAt(0));
	}

	@Test
	public void testRTStr() {
		assertEquals("foo", RT.toString(Symbols.FOO));
		assertEquals(":foo", RT.toString(Keywords.FOO));
	}

	@Test
	public void testRTPrint() {
		AString s = Strings.create("\n");
		assertEquals("\"\\n\"", RT.print(s, 100).toString());

		assertEquals(Strings.NIL, RT.print(null, 3));
		assertEquals(null, RT.print(null, 1));
	}

	@Test
	public void testPrint() {
		assertEquals("\"\"", Strings.empty().print(2).toString());
		assertEquals("\"foo bar\"", Strings.create("foo bar").print().toString());
	}

	@Test
	public void testPrintExceeded() {
		AString s = Strings.create("foobar");
		String exp = "\"f" + Constants.PRINT_EXCEEDED_MESSAGE;
		assertEquals(exp, s.print(2).toString());

		assertEquals(Constants.PRINT_EXCEEDED_MESSAGE, s.print(0));
	}

	@Test
	public void testIntAt() {
		AString s = Strings.create(Blob.fromHex("12345678abcd"));
		assertEquals(0x12345678, s.intAt(0));
		assertEquals(0x345678ab, s.intAt(1));
		assertEquals(0x5678abcd, s.intAt(2));
		assertEquals(0xabcdffff, s.intAt(4));
		assertEquals(0xffffffff, s.intAt(6)); // 0xff beyond end of string
		assertEquals(0xffffffff, s.intAt(-6)); // 0xff before start of string
	}
	


	@Test
	public void testCharAt() {
		testCharAt("ab", 1, 'b');
		testCharAt("ab", 2, null);
		testCharAt("\u1234\u1235", 0, '\u1234');
		testCharAt("", 0, null);

		testCharAt(Strings.fromHex("65ff65"), 1, null);
	}

	private void testCharAt(String string, int i, Character c) {
		AString s = Strings.create(string);
		testCharAt(s, i, c);
	}

	private void testCharAt(AString s, int i, Character c) {
		int cp = s.charAt(i);
		CVMChar cg = s.get(i);
		assertEquals(cg, CVMChar.create(cp));
		if (c != null) {
			assertEquals(CVMChar.create(c), cg);
		}
	}

	@Test
	public void testIntern() {
		// Test that interning a Symbol results in the same symbol for subsequent creates
		AString s1 = Strings.intern("shouldBeInterned");
		AString s2 = Strings.create("shouldBeInterned");
		assertSame(s1, s2);

		// Test that creating a Symbol with the same name does not intern the same symbol
		AString s3 = Strings.create("shouldNotBeInterned");
		AString s4 = Strings.create("shouldNotBeInterned");
		assertNotSame(s3, s4);

		// Test that the Symbols class is interning the correct symbols
		assertSame(Strings.ADDRESS,Strings.create("address"));
	}

	@Test public void testInternRefs() {
		AString s1=Strings.intern("interned");
		AString s2=Strings.intern("interned");
		assertSame(s1,s2);
		AString s3=Strings.intern(s1);
		assertSame(s1,s3);
		assertSame(s1.toFlatBlob(),s3.toFlatBlob());
		
		assertTrue(s1.getRef().isInternal());
		assertSame(ErrorCodes.TIMEOUT,Keyword.create("TIMEOUT"));
	}

	// ========== toUpperCase Tests ==========

	@Test
	public void testToUpperCase() {
		// Basic conversion
		assertEquals("HELLO", Strings.create("hello").toUpperCase().toString());
		assertEquals("HELLO", Strings.create("Hello").toUpperCase().toString());
		assertEquals("HELLO WORLD", Strings.create("hello world").toUpperCase().toString());

		// Already uppercase - should return same instance
		AString upper = Strings.create("HELLO");
		assertSame(upper, upper.toUpperCase());

		// Numbers and punctuation - unchanged, return same instance
		AString nums = Strings.create("123!@#");
		assertSame(nums, nums.toUpperCase());

		// Mixed - should change
		AString mixed = Strings.create("Hello123");
		AString mixedUpper = mixed.toUpperCase();
		assertEquals("HELLO123", mixedUpper.toString());
		assertNotSame(mixed, mixedUpper);

		// Empty string - should return same instance
		assertSame(StringShort.EMPTY, StringShort.EMPTY.toUpperCase());

		// Unicode characters
		assertEquals("CAFÉ", Strings.create("café").toUpperCase().toString());
	}

	@Test
	public void testToUpperCaseSameInstance() {
		// Test various strings that are already uppercase
		String[] alreadyUpper = {"", "A", "ABC", "HELLO WORLD", "123", "!@#$%", "ABC123!@#"};
		for (String s : alreadyUpper) {
			AString str = Strings.create(s);
			assertSame(str, str.toUpperCase(), "Should return same instance for: " + s);
		}
	}

	// ========== toLowerCase Tests ==========

	@Test
	public void testToLowerCase() {
		// Basic conversion
		assertEquals("hello", Strings.create("HELLO").toLowerCase().toString());
		assertEquals("hello", Strings.create("Hello").toLowerCase().toString());
		assertEquals("hello world", Strings.create("HELLO WORLD").toLowerCase().toString());

		// Already lowercase - should return same instance
		AString lower = Strings.create("hello");
		assertSame(lower, lower.toLowerCase());

		// Numbers and punctuation - unchanged, return same instance
		AString nums = Strings.create("123!@#");
		assertSame(nums, nums.toLowerCase());

		// Mixed - should change
		AString mixed = Strings.create("Hello123");
		AString mixedLower = mixed.toLowerCase();
		assertEquals("hello123", mixedLower.toString());
		assertNotSame(mixed, mixedLower);

		// Empty string - should return same instance
		assertSame(StringShort.EMPTY, StringShort.EMPTY.toLowerCase());

		// Unicode characters
		assertEquals("café", Strings.create("CAFÉ").toLowerCase().toString());
	}

	@Test
	public void testToLowerCaseSameInstance() {
		// Test various strings that are already lowercase
		String[] alreadyLower = {"", "a", "abc", "hello world", "123", "!@#$%", "abc123!@#"};
		for (String s : alreadyLower) {
			AString str = Strings.create(s);
			assertSame(str, str.toLowerCase(), "Should return same instance for: " + s);
		}
	}

	// ========== trim Tests ==========

	@Test
	public void testTrim() {
		// Basic trimming
		assertEquals("hello", Strings.create("  hello  ").trim().toString());
		assertEquals("hello", Strings.create("hello  ").trim().toString());
		assertEquals("hello", Strings.create("  hello").trim().toString());
		assertEquals("hello world", Strings.create("  hello world  ").trim().toString());

		// Tabs, newlines, carriage returns
		assertEquals("hello", Strings.create("\t\nhello\r\n").trim().toString());
		assertEquals("hello", Strings.create(" \t \n hello \r \n ").trim().toString());

		// Already trimmed - should return same instance
		AString trimmed = Strings.create("hello");
		assertSame(trimmed, trimmed.trim());

		AString trimmedSpaces = Strings.create("hello world");
		assertSame(trimmedSpaces, trimmedSpaces.trim());

		// Empty string - should return same instance
		assertSame(StringShort.EMPTY, StringShort.EMPTY.trim());

		// All whitespace - should return empty
		assertEquals("", Strings.create("   ").trim().toString());
		assertEquals("", Strings.create("\t\n\r ").trim().toString());

		// Single character
		assertEquals("a", Strings.create(" a ").trim().toString());
		AString singleChar = Strings.create("a");
		assertSame(singleChar, singleChar.trim());
	}

	@Test
	public void testTrimSameInstance() {
		// Test various strings that don't need trimming
		String[] noTrim = {"", "a", "abc", "hello world", "123", "!@#$%", "a b c"};
		for (String s : noTrim) {
			AString str = Strings.create(s);
			assertSame(str, str.trim(), "Should return same instance for: '" + s + "'");
		}
	}

	@Test
	public void testTrimPreservesInternalSpaces() {
		// Trimming should only affect leading/trailing whitespace
		assertEquals("hello  world", Strings.create("  hello  world  ").trim().toString());
		assertEquals("a b c d", Strings.create(" a b c d ").trim().toString());
		assertEquals("a\tb\nc", Strings.create(" a\tb\nc ").trim().toString());
	}

	@Test
	public void testTrimWithUnicode() {
		// Unicode characters that are not ASCII whitespace should not be trimmed
		AString unicode = Strings.create("\u1234hello\u1235");
		assertSame(unicode, unicode.trim());

		// But ASCII whitespace around unicode should be trimmed
		assertEquals("\u1234hello\u1235", Strings.create("  \u1234hello\u1235  ").trim().toString());
	}

	// ========== Combined Tests ==========

	@Test
	public void testCaseAndTrimCombined() {
		AString s = Strings.create("  Hello World  ");
		assertEquals("HELLO WORLD", s.trim().toUpperCase().toString());
		assertEquals("hello world", s.trim().toLowerCase().toString());
	}

	@Test
	public void testCasePreservesLength() {
		// Upper/lower case should preserve character count for ASCII
		AString s = Strings.create("Hello World");
		assertEquals(s.toString().length(), s.toUpperCase().toString().length());
		assertEquals(s.toString().length(), s.toLowerCase().toString().length());
	}

	public void doStringTest(AString a) {
		long n = a.count();
		assertEquals(Strings.EXCESS_BYTE, a.byteAt(-1));
		assertEquals(Strings.EXCESS_BYTE, a.byteAt(n));

		// get should return null for invalid positions
		assertNull(a.get(-1));
		assertNull(a.get(n));

		// Round trip to Java String
		String js = a.toString();
		AString b = Strings.create(js);
		assertEquals(a.count(), b.count());
		assertEquals(a, b);
		assertEquals(0, a.compareTo(b.toBlob()));

		// Round Trip to Blob
		ABlob bs = a.toBlob();
		AString abs = Strings.create(bs);
		assertEquals(a, abs);

		// JSON round trip as String
		assertEquals(a, JSON.parseJSON5(JSON.toString(a)));

		// JSON escape / unescape
		assertEquals(a, JSON.unescape(JSON.escape(js).toString()));

		// fall back to bloblike tests
		BlobsTest.doBlobLikeTests(a);
	}
}
