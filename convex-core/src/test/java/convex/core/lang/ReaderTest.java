package convex.core.lang;

import static convex.test.Assertions.assertCVMEquals;
import static convex.test.Assertions.assertParseException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.Result;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.Symbols;
import convex.core.cvm.Syntax;
import convex.core.data.ACell;
import convex.core.data.AList;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Lists;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.Symbol;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBigInteger;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.ParseException;
import convex.core.text.Text;
import convex.test.Samples;

/**
 * Test class for general Reader behaviour. More specific ANTLR tests are also provided in ANTLRTest
 */
public class ReaderTest {

	@Test
	public void testVectors() {
		assertSame(Vectors.empty(), Reader.read("[]"));
		assertSame(Vectors.empty(), Reader.read(" [ ] "));
		
		assertEquals(Vectors.of(1L,-2L), Reader.read("[1 -2]"));

		assertEquals(Vectors.of(Samples.FOO), Reader.read(" [ :foo ] "));
		assertEquals(Vectors.of(Vectors.empty()), Reader.read(" [ [] ] "));
	}

	@Test
	public void testKeywords() {
		assertEquals(Samples.FOO, Reader.read(":foo"));
		assertEquals(Keyword.create("foo.bar"), Reader.read(":foo.bar"));
		
		// : is currently a valid inner Keyword character
		assertEquals(Keyword.create("foo:bar"), Reader.read(":foo:bar"));

		// keywords can start with more than one colon
		assertEquals(Keyword.create(":foo"), Reader.read("::foo"));
		
		assertEquals(Keyword.create("nil"), Reader.read(":nil"));
		
		// special case, since "/" is a valid name on its own
		assertEquals(Keyword.create("/"), Reader.read(":/"));
		
		assertThrows(ParseException.class,()->Reader.read(":"));

	}

	@Test
	public void testBadKeywords() {
		assertParseException( () -> Reader.read(":"));
		assertParseException( () -> Reader.read(" : "));
		
		// Case spotted in #441
		assertParseException(()->Reader.readAll("() : ()")); 
	}

	@Test
	public void testComment() {
		assertCVMEquals(1L, Reader.read(";this is a comment\n 1 \n"));
		assertCVMEquals(Vectors.of(2L), Reader.read("[#_foo 2]"));
		assertCVMEquals(Vectors.of(3L), Reader.read("[3 #_foo]"));
	}

	@Test
	public void testSymbols() {
		assertEquals(Symbols.FOO, Reader.read("foo"));
		assertEquals(Lists.of(Symbols.LOOKUP,Address.create(666),Symbols.FOO), Reader.read("#666/foo"));
		
		assertEquals(Lists.of(Symbol.create("+"), 1L), Reader.read("(+ 1)"));
		assertEquals(Lists.of(Symbol.create("+a")), Reader.read("( +a )"));
		assertEquals(Lists.of(Symbol.create("/")), Reader.read("(/)"));
		assertEquals(Lists.of(Symbols.LOOKUP,Symbols.FOO,Symbols.BAR), Reader.read("foo/bar"));
		assertEquals(Symbol.create("a*+!-_?<>=!"), Reader.read("a*+!-_?<>=!"));
		assertEquals(Symbol.create("foo.bar"), Reader.read("foo.bar"));
		assertEquals(Symbol.create(".bar"), Reader.read(".bar"));
		
		assertEquals(Lists.of(Symbols.QUOTE,null), Reader.read("'nil")); // sane?
		
		// Interpret leading dot as symbols always. Addresses Issue #65
		assertEquals(Symbol.create(".56"), Reader.read(".56"));

		// Bad address parsing
		assertParseException(()->Reader.read("#-1/foo"));
		
		// Pipe not yet a valid symbol? Clojure doesn't allow but perhaps we should
		assertParseException(()->Reader.read("|"));
		
		// too long symbol names
		assertParseException(()->Reader.read(Samples.TOO_BIG_SYMBOLIC));
		assertParseException(()->Reader.read(Samples.TOO_BIG_SYMBOLIC+"/a"));
		assertParseException(()->Reader.read("a/"+Samples.TOO_BIG_SYMBOLIC));

	}
	
	@Test 
	public void testSymbolPath() {
		ACell form=Reader.read("foo/bar/baz");
		assertEquals(Lists.of(Symbols.LOOKUP,Lists.of(Symbols.LOOKUP,Symbols.FOO,Symbols.BAR),Symbols.BAZ),form) ;

		assertParseException(()->Reader.read("foo/12"));
		
		// space after slash not valid
		assertParseException(()->Reader.read("foo/ bar"));
		assertParseException(()->Reader.read("foo / bar")); // technically 3 symbols

		assertEquals(Lists.of(Symbols.LOOKUP,Address.ZERO,Symbols.FOO),Reader.read("#0/foo"));
		assertEquals(Lists.of(Symbols.LOOKUP,Address.ZERO,Symbols.DIVIDE),Reader.read("#0//"));
	}
	
	@Test 
	public void testColonIssue462() {
		String s462="() : ()";
		assertParseException(()->Reader.readAll(s462));
		
	}
	
	@Test
	public void testExtraInputRegression244() {
		assertParseException(()->Reader.readAll("'(42))))"));
	}

	@Test
	public void testSymbolsRegressionCases() {
		// symbol staring with "nil"
		assertEquals(Symbol.create("nils"), Reader.read("nils"));

		// symbol starting with a boolean value
		assertEquals(Symbol.create("falsey"), Reader.read("falsey"));
		assertEquals(Symbol.create("true-exp"), Reader.read("true-exp"));
	}

	@Test
	public void testChar() {
		assertCVMEquals('A', Reader.read("\\A"));
		assertCVMEquals('a', Reader.read("\\u0061"));
		assertCVMEquals(' ', Reader.read("\\space"));
		assertCVMEquals('\t', Reader.read("\\tab"));
		assertCVMEquals('\n', Reader.read("\\newline"));
		assertCVMEquals('\f', Reader.read("\\formfeed"));
		assertCVMEquals('\b', Reader.read("\\backspace"));
		assertCVMEquals('\r', Reader.read("\\return"));
		
		assertCVMEquals('|', Reader.read("\\|"));
	}
	
	@Test
	public void testAddress() {
		assertEquals(Address.ZERO, Reader.read("#0"));

		assertParseException(()->Reader.read(" # 0 "));
	}
	
	@Test
	public void testBooleans() {
		assertSame(CVMBool.TRUE, Reader.read("true"));
		assertSame(CVMBool.FALSE, Reader.read(" false"));
		assertSame(CVMBool.TRUE, Reader.read("#[B1]"));
		assertSame(CVMBool.FALSE, Reader.read("#[b0]"));
	}

	@Test
	public void testNumbers() {
		assertCVMEquals(1L, Reader.read("1"));
		assertCVMEquals(2.0, Reader.read("2.0"));
		
		// scientific notation
		assertCVMEquals(2.0, Reader.read("2.0e0"));
		assertCVMEquals(20.0, Reader.read("2.0e1"));
		assertCVMEquals(0.2, Reader.read("2.0e-1"));
		assertCVMEquals(12.0, Reader.read("12e0"));
		
		
		assertParseException(() -> {
			Reader.read("2.0e0.1234");
		});
		assertParseException( () -> Reader.read("[2.0e0.1234]")); // Issue #70

		// metadata ignored
		assertEquals(Syntax.create(RT.cvm(3.23),Maps.of(Keywords.FOO, CVMBool.TRUE)), Reader.read("^:foo 3.23"));
	}
	
	@Test
	public void testIntegers() {
		assertEquals(CVMLong.ONE,Reader.read("1"));
		assertEquals(CVMBigInteger.MIN_POSITIVE,Reader.read("9223372036854775808"));
		assertEquals(CVMLong.MAX_VALUE,Reader.read("9223372036854775807"));
		assertEquals(CVMLong.MIN_VALUE,Reader.read("-9223372036854775808"));
		assertEquals(CVMBigInteger.MIN_NEGATIVE,Reader.read("-9223372036854775809"));
	}
	
	@Test
	public void testSpecialNumbers() {
		assertNotEquals(CVMDouble.NaN, Reader.read("#[1d7ff8000000ffffff]"));
		assertEquals(CVMDouble.NaN, Reader.read("##NaN"));
		assertEquals(CVMDouble.POSITIVE_INFINITY, Reader.read("##Inf "));
		assertEquals(CVMDouble.NEGATIVE_INFINITY, Reader.read(" ##-Inf"));
		
		// A non-CVM NaN
		doReadPrintTest("#[1d7ff8000000ffffff]");
		doReadPrintTest("##NaN");
	}
	
	@Test
	public void testHexBlobs() {
		assertEquals(Blobs.fromHex("cafebabe"), Reader.read("0xcafebabe"));
		assertEquals(Blobs.fromHex("0aA1"), Reader.read("0x0Aa1"));
		assertEquals(Blob.EMPTY, Reader.read("0x"));
	
		// TODO: figure out the edge case
		assertParseException(() -> Reader.read("0x1"));
		assertParseException( () -> Reader.read("[0x1]")); // odd number of hex digits

		assertParseException(() -> Reader.read("0x123")); // odd number of hex digits
	}

	@Test
	public void testNil() {
		assertNull(Reader.read("nil"));

		// metadata on null
		assertEquals(Syntax.create(null),Reader.read("^{} nil"));
	}

	@Test
	public void testStrings() {
		assertSame(Strings.empty(), Reader.read("\"\""));
		assertEquals(Strings.create("bar"), Reader.read("\"bar\""));
		assertEquals(Vectors.of(Strings.create("bar")), Reader.read("[\"bar\"]"));
		assertEquals(Strings.create("\"bar\""), Reader.read("\"\\\"bar\\\"\""));
		
		assertEquals(Strings.create("\n"), Reader.read("\"\\n\""));

		// Multi-line String
		assertEquals(Strings.create("\n"), Reader.read("\"\n\""));
	}

	@Test
	public void testList() {
		assertSame(Lists.empty(), Reader.read(" ()"));
		assertEquals(Lists.of(1L, 2L), Reader.read("(1 2)"));
		assertEquals(Lists.of(Vectors.empty()), Reader.read(" ([] )"));
	}
	
	@Test
	public void testSets() {
		assertSame(Sets.empty(), Reader.read("#{}"));
		assertEquals(Sets.of(1L, 2L), Reader.read("#{1 2}"));
		assertEquals(Sets.of(1L, 2L), Reader.read("#{1 2 2 1}"));
		assertEquals(Sets.of(Vectors.empty()), Reader.read("#{[]}"));
		assertParseException(()->Reader.read("# {}"));
	}

	@Test
	public void testNoWhiteSpace() {
		assertEquals(Lists.of(Vectors.empty(), Vectors.empty()), Reader.read("([][])"));
		assertEquals(Lists.of(Vectors.empty(), 13L), Reader.read("([]13)"));
		assertEquals(Lists.of(Symbols.SET, Vectors.empty()), Reader.read("(set[])"));
	}

	@Test
	public void testMaps() {
		assertSame(Maps.empty(), Reader.read("{}"));
		assertEquals(Maps.of(1L, 2L), Reader.read("{1,2}"));
		assertEquals(Maps.of(Samples.FOO, Samples.BAR), Reader.read("{:foo :bar}"));
	}
	
	@Test
	public void testMapError() {
		assertParseException(()->Reader.read("{1}"));
		assertParseException(()->Reader.read("{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}"));
	}

	@Test
	public void testQuote() {
		assertEquals(Reader.read("(quote (lookup foo bar))"),Reader.read("'foo/bar"));
		
		assertEquals(Lists.of(Symbols.QUOTE, 1L), Reader.read("'1"));
		assertEquals(Lists.of(Symbols.QUOTE, Lists.of(Symbols.QUOTE, Vectors.empty())), Reader.read("''[]"));
		
		assertEquals(Lists.of(Symbols.QUOTE,Lists.of(Symbols.UNQUOTE,Symbols.FOO)),Reader.read("'~foo"));

	}
	
	@Test 
	public void testResolve() {
		assertEquals(Lists.of(Symbols.RESOLVE, Symbols.FOO), Reader.read("@foo"));
		assertParseException(() -> Reader.read("@(foo)"));
		assertParseException(() -> Reader.read("@ foo"));
	}
	
	@Test public void testUnprintablePrint() {
		AString bad=Strings.create(Blob.fromHex("ff")); // bad UTF-8
		AString pbad=RT.print(bad);
		AString expected=Strings.create("\"\uFFFD\"");
		assertEquals(expected,pbad);
		
		String ps=pbad.toString();
		assertNotEquals(bad,Reader.read(ps)); // not reproducing the bad UTF-8
		assertEquals(pbad,Reader.read(ps).print()); // printed version should round trip
		doReadPrintTest(ps);
	}
	
	@Test
	public void testTooManyClosingParens() {
		// See #244
		assertParseException( () -> Reader.read("(42))))"));
	}


	@Test
	public void testWrongSizeMaps() {
		assertParseException(() -> Reader.read("{:foobar}"));
	}

	@Test
	public void testParsingNothing() {
		assertParseException(() -> Reader.read("  "));
	}

	@Test
	public void testSyntaxReader() {
		assertEquals(Syntax.class, Reader.readSyntax("nil").getClass());
		assertEquals(Syntax.create(RT.cvm(1L)), Reader.readSyntax("1").withoutMeta());
		assertEquals(Syntax.create(Symbols.FOO), Reader.readSyntax("foo").withoutMeta());
		assertEquals(Syntax.create(Keywords.FOO), Reader.readSyntax(":foo").withoutMeta());
	}
	
	@Test
	public void testReadMetadata() {
		assertEquals(Syntax.create(Keywords.FOO),Reader.read("^{} :foo"));
		assertEquals(Syntax.create(Keywords.FOO),Reader.read("^ {}:foo"));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void testMetadata() {
		assertCVMEquals(Boolean.TRUE, Reader.readSyntax("^:foo a").getMeta().get(Keywords.FOO));
		
		{
			AList<ACell> def=(AList<ACell>) Reader.readAll("(def ^{:foo 2} a 1)").get(0);
			Syntax form=(Syntax) def.get(1);
			
			assertCVMEquals(2L, form.getMeta().get(Keywords.FOO));
		}

	}
	
	@Test public void testIdempotentToString() {
		doIdempotencyTest(null);
		doIdempotencyTest(Address.create(12345));
		doIdempotencyTest(CVMBool.TRUE);
		doIdempotencyTest(Samples.LONG_MAP_10);
		doIdempotencyTest(Samples.BAD_HASH);
		doIdempotencyTest(Samples.INT_INDEX_7);
		doIdempotencyTest(Reader.readAll("(def ^{:foo 2} a 1)"));
		doIdempotencyTest(Reader.readAll("(fn ^{:foo 2} [] bar/baz)"));
		
		// small signed data (embedded)
		doIdempotencyTest(Samples.KEY_PAIR.signData(CVMLong.MAX_VALUE));

		// moderate sized sized data
		doIdempotencyTest(Samples.KEY_PAIR.signData(Samples.INT_VECTOR_256));

	}
	
	public void doIdempotencyTest(ACell cell) {
		String s=RT.toString(cell);
		assertEquals(s,RT.toString(Reader.read(s)));
	}
	
	@Test public void testTagged() {
		// Unrecognised tags
		assertEquals(null,Reader.read("#foo nil"));
		assertEquals(Vectors.empty(),Reader.read("#foo []"));
		
		// Index types
		assertEquals(Index.EMPTY,Reader.read("#Index {}"));
		assertEquals(Index.of(Blob.EMPTY,CVMLong.ONE),Reader.read("#Index {0x 1}"));
		assertEquals(Index.of(Blob.fromHex("1234"),CVMLong.ONE,Blob.fromHex("12"),CVMLong.ZERO),Reader.read("#Index {0x12 0 0x1234 1}"));
		assertParseException(()->Reader.read("#Index nil"));
		assertParseException(()->Reader.read("#Index {true false}"));
		
		// Result types
		assertEquals(Result.create(CVMLong.ZERO,null),Reader.read("#Result {:id 0}"));
	}
	
	/**
	 * Test cases that should read and print identically
	 */
	@Test public void testReadPrint() {
		doReadPrintTest("nil");
		doReadPrintTest("\\a"); // Literal character
		doReadPrintTest("\\newline"); // Literal escaped character
		doReadPrintTest("\\space"); // Literal escaped character
		doReadPrintTest("\"\""); // empty string
		doReadPrintTest("\"[\""); // string containing a single square bracket should be OK
		doReadPrintTest("1.0");
		doReadPrintTest("[:foo bar]");
		doReadPrintTest("#Index {}");
		doReadPrintTest("^{} 0xa89e59cc8ab9fc6a13785a37938c85b306b24663415effc01063a6e25ef52ebcd3647d3a77e0a33908a372146fdccab6");
	}
	
	/**
	 * Test cases for strings with Java escapes
	 */
	@Test public void testJavaEscapes() {
		doEscapeTest("!0\\","\\410\\");
	}
	
	private void doEscapeTest(String raw, String escaped) {
		assertEquals(raw,Text.unescapeJava(escaped));
	}

	/**
	 * Test cases that should read and print identically
	 */
	@Test public void testReadPrintStringEscapes() {
		doReadPrintTest("\"\\\\\""); // backslash
		doReadPrintTest("\"\\n\""); // newline		
		doReadPrintTest("\"Can't convert value of type Long to type Sequence\""); // single quote see #407		
	}
	
	/**
	 * Tests for special escape sequences in strings (octal, unicode)
	 */
	@Test public void testStringSpecialEscapes() {
		assertEquals("S", Reader.read("\"\\123\"").toString()); // S = 83 dec
		assertEquals("S", Reader.read("\"\\u0053\"").toString()); // S = 53 hex
	}


	private void doReadPrintTest(String js) {
		AString s=Strings.create(js);
		ACell cell=Reader.read(js);
		AString printed=RT.print(cell, Long.MAX_VALUE);
		assertEquals(s,printed);
		String js2=printed.toString();
		assertEquals(js,js2);
	}
	
	/**
	 * Test cases for CVM values that should print and read correctly
	 */
	@Test public void testPrintRead() {
		doPrintReadTest(null);
		doPrintReadTest(Vectors.of(1,Symbols.FOO, Keywords.BAR));
		doPrintReadTest(Samples.MAX_EMBEDDED_BLOB);
	}
	
	private void doPrintReadTest(ACell a) {
		AString printed=RT.print(a, Long.MAX_VALUE);
		String js=printed.toString();
		ACell b=Reader.read(js);
		assertEquals(a,b);
	}
}
