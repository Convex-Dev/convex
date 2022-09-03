package convex.core.lang;

import static convex.test.Assertions.assertCVMEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AList;
import convex.core.data.AString;
import convex.core.data.Address;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Lists;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMDouble;
import convex.core.exceptions.ParseException;
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
		
		// : is currently a valid symbol character
		assertEquals(Keyword.create("foo:bar"), Reader.read(":foo:bar"));

	}

	@Test
	public void testBadKeywords() {
		assertThrows(ParseException.class, () -> Reader.read(":"));
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
		
		// Interpret leading dot as symbols always. Addresses Issue #65
		assertEquals(Symbol.create(".56"), Reader.read(".56"));

		// TODO: maybe this should be possible?
		// namespaces cannot themselves be qualified
		//assertThrows(ParseException.class,()->Reader.read("a/b/c"));
		
		// Bad address parsing
		assertThrows(ParseException.class,()->Reader.read("#-1/foo"));
		
		// Pipe not yet a valid symbol?
		assertThrows(ParseException.class,()->Reader.read("|"));
		
		// too long symbol names
		assertThrows(ParseException.class,()->Reader.read(Samples.TOO_BIG_SYMBOLIC));
		assertThrows(ParseException.class,()->Reader.read(Samples.TOO_BIG_SYMBOLIC+"/a"));
		assertThrows(ParseException.class,()->Reader.read("a/"+Samples.TOO_BIG_SYMBOLIC));

	}
	
	@Test 
	public void testSymbolPath() {
		ACell form=Reader.read("foo/bar/baz");
		assertEquals(Lists.of(Symbols.LOOKUP,Lists.of(Symbols.LOOKUP,Symbols.FOO,Symbols.BAR),Symbols.BAZ),form) ;
	}

	@Test
	public void testSymbolsRegressionCases() {
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
	public void testNumbers() {
		assertCVMEquals(1L, Reader.read("1"));
		assertCVMEquals(2.0, Reader.read("2.0"));
		
		// scientific notation
		assertCVMEquals(2.0, Reader.read("2.0e0"));
		assertCVMEquals(20.0, Reader.read("2.0e1"));
		assertCVMEquals(0.2, Reader.read("2.0e-1"));
		assertCVMEquals(12.0, Reader.read("12e0"));
		
		assertThrows(ParseException.class, () -> {
			Reader.read("2.0e0.1234");
		});
		// assertNull( Reader.read("[2.0e0.1234]"));
		// TODO: do we want this?
		//assertThrows(Error.class, () -> Reader.read("[2.0e0.1234]")); // Issue #70

		// metadata ignored
		assertEquals(Syntax.create(RT.cvm(3.23),Maps.of(Keywords.FOO, CVMBool.TRUE)), Reader.read("^:foo 3.23"));
	}
	
	@Test
	public void testSpecialNumbers() {
		assertEquals(CVMDouble.NaN, Reader.read("##NaN"));
		assertEquals(CVMDouble.POSITIVE_INFINITY, Reader.read("##Inf "));
		assertEquals(CVMDouble.NEGATIVE_INFINITY, Reader.read(" ##-Inf"));
	}
	
	@Test
	public void testHexBlobs() {
		assertEquals(Blobs.fromHex("cafebabe"), Reader.read("0xcafebabe"));
		assertEquals(Blobs.fromHex("0aA1"), Reader.read("0x0Aa1"));
		assertEquals(Blob.EMPTY, Reader.read("0x"));
	
		// TODO: figure out the edge case
		assertThrows(ParseException.class, () -> Reader.read("0x1"));
		//assertThrows(Error.class, () -> Reader.read("[0x1]")); // odd number of hex digits

		assertThrows(ParseException.class, () -> Reader.read("0x123")); // odd number of hex digits
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

	}

	@Test
	public void testList() {
		assertSame(Lists.empty(), Reader.read(" ()"));
		assertEquals(Lists.of(1L, 2L), Reader.read("(1 2)"));
		assertEquals(Lists.of(Vectors.empty()), Reader.read(" ([] )"));
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
		assertThrows(ParseException.class,()->Reader.read("{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}}"));
	}

	@Test
	public void testQuote() {
		assertEquals(Lists.of(Symbols.QUOTE, 1L), Reader.read("'1"));
		assertEquals(Lists.of(Symbols.QUOTE, Lists.of(Symbols.QUOTE, Vectors.empty())), Reader.read("''[]"));
		
		assertEquals(Lists.of(Symbols.QUOTE,Lists.of(Symbols.UNQUOTE,Symbols.FOO)),Reader.read("'~foo"));

	}
	

	@Test
	public void testTooManyClosingParens() {
		// See #244
		assertThrows(ParseException.class, () -> Reader.read("(42))))"));
	}


	@Test
	public void testWrongSizeMaps() {
		assertThrows(ParseException.class, () -> Reader.read("{:foobar}"));
	}

	@Test
	public void testParsingNothing() {
		assertThrows(ParseException.class, () -> Reader.read("  "));
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
		doIdempotencyTest(Reader.readAll("(def ^{:foo 2} a 1)"));
		doIdempotencyTest(Reader.readAll("(fn ^{:foo 2} [] bar/baz)"));
	}
	
	public void doIdempotencyTest(ACell cell) {
		String s=RT.toString(cell);
		assertEquals(s,RT.toString(Reader.read(s)));
	}
	
	/**
	 * Test cases that should read and print identically
	 */
	@Test public void testReadPrint() {
		doReadPrintTest("nil");
		doReadPrintTest("\\a");
		doReadPrintTest("1.0");
		doReadPrintTest("[:foo bar]");
		doReadPrintTest("^{} 0xa89e59cc8ab9fc6a13785a37938c85b306b24663415effc01063a6e25ef52ebcd3647d3a77e0a33908a372146fdccab6");
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
