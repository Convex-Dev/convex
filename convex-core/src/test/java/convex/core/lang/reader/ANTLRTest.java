package convex.core.lang.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.Symbols;
import convex.core.cvm.Syntax;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Keyword;
import convex.core.data.Lists;
import convex.core.data.Maps;
import convex.core.data.ObjectsTest;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.Symbol;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBigInteger;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMChar;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.ParseException;
import convex.core.lang.Reader;

public class ANTLRTest {
	
	@SuppressWarnings("unchecked")
	private <R extends ACell> R read(String s) {
		return (R) AntlrReader.read(s);
	}
	
	@SuppressWarnings("unchecked")
	private <R extends ACell> R readAll(String s) {
		return (R) AntlrReader.readAll(s);
	}

	@Test public void testNil() {
		assertNull(read("nil"));
	}
		
	@Test public void testBooleans () {
		assertSame(CVMBool.TRUE,read("true"));
		assertSame(CVMBool.FALSE,read("false"));
	}
	
	@Test public void testLongs () {
		assertEquals(CVMLong.create(17),read("17"));
		assertEquals(CVMLong.create(-2),read("-2"));
		assertSame(CVMLong.ZERO,read("0"));
		
		// assertParseError("-999999999999999999999999999999999999999999999");
		// assertParseError("999999999999999999999999999999999999999999999");
	}
	
	@Test public  void testBigInts() {
		String s="999999999999999999999999999999999999999999999";
		BigInteger bi=new BigInteger(s);
		
		CVMBigInteger bint = read(s);
		assertEquals(bi, bint.getBigInteger());

		CVMBigInteger nint = read("-"+s);
		assertEquals(bi.negate(), nint.getBigInteger());
	}
		
	@Test public void testVectors() {
		assertEquals(Vectors.of(1,2),read("[1 2]"));
	}
	
	@Test public void testLists() {
		assertEquals(Lists.of(1,2),read("(1 2)"));
	}
	
	@Test public void testSets() {
		assertEquals(Sets.of(1,2),read("#{1 2}"));
	}
	
	@Test public void testMaps() {
		assertEquals(Maps.of(1,2),read("{1 2}"));
		assertEquals(Maps.of(1,2),read("{1\n2}"));
	}
		
	@Test public void testEmptyDataStructures() {
		// empty structures
		assertSame(Sets.empty(),read("#{}"));
		assertSame(Lists.empty(),read("()"));
		assertSame(Vectors.empty(),read("[]"));
		assertSame(Maps.empty(),read("{}"));
	}
	
	@Test public void testSymbols() {
		// BAsic symbol
		assertEquals(Symbols.FOO,read("foo"));
		
		// Single slash should be a symbol
		assertEquals(Symbol.create("/"),read("/"));

		// TODO: Single pipe should be a symbol?? Or parse error?
		// assertEquals(Symbol.create("|"),read("|"));

		
		// Does should count in symbol
		assertEquals(Symbol.create("convex.world"),read("convex.world"));
		
		// Check numbers are consumed in symbolic name
		assertEquals(Symbol.create("reg-123"),read("reg-123"));
	}
	
	@Test public void testKeywords() {
		assertEquals(Keywords.FOO,read(":foo"));
		assertEquals(Keyword.create("/"),read(":/"));
		
		assertParseError(":");
		assertParseError(":0");
	}

		
	@Test public void testBlobs() {
		// Blobs
		assertEquals(Blob.EMPTY,read("0x"));
		assertEquals(Blob.fromHex("cafebabe"),read("0xcaFEBAbe"));
		
		assertParseError("0x0");

	}
	
	@Test public void testParens() {
		assertParseError("(");
		assertParseError(")");
		assertParseError("[");
		assertParseError("]");
		assertParseError("{");
		assertParseError("}");
	}
	
	@Test public void testAddress() {
		// Address
		assertEquals(Address.create(17),read("#17"));
		
		assertParseError("#-1"); // negative
		assertParseError("#9999999999999999999999999999999999999999999999999999999"); // too big
	}
	
	@Test public void testSyntax() {
		assertEquals(Syntax.create(CVMLong.ONE,Maps.empty()), read("^{} 1"));
	}
	
	@Test public void testQuoting() {
		assertEquals(Lists.of(Symbols.QUOTE,CVMLong.ZERO), read("'0"));
		assertEquals(Lists.of(Symbols.QUOTE,Symbols.FOO), read("'foo"));
		assertEquals(Lists.of(Symbols.QUOTE,Vectors.empty()), read("'[]"));
	}
	

	@Test public void testChars() {
		assertEquals(CVMChar.create('a'), read("\\a"));
		assertEquals(CVMChar.create('\t'), read("\\tab"));
	}
	
	@Test public void testSpecial() {
		assertEquals(CVMDouble.NaN, read("##NaN"));
	}
	
	
	@Test public void testDouble() {
		assertEquals(CVMDouble.ONE, read("1.0"));
		assertEquals(CVMDouble.ONE, read("1.0e0"));
		assertEquals(CVMDouble.create(-17.0), read("-17.0"));
		assertEquals(CVMDouble.create(-17.0e2), read("-17.0E2"));
		assertEquals(CVMDouble.create(1000), read("1e3"));
		assertEquals(CVMDouble.create(0.001), read("1e-3"));
	}
	
	@Test public void testStrings() {
		assertSame(Strings.empty(), read("\"\""));
		assertEquals(Strings.create("a"), read("\"a\""));
		assertEquals(Strings.create("'"), read("\"'\"")); // Single quote See #407
		assertEquals(Strings.create("bar"), read("\"bar\""));
		assertEquals(Strings.create("ba\nr"), read("\"ba\\nr\""));
	}
	
	@Test public void testReadAll() {
		assertSame(Lists.empty(),readAll(""));
		assertEquals(Lists.of(1,2),readAll(" 1 2 "));
		
		assertThrows(ParseException.class,()->readAll("1 2 ("));
	}

	@Test public void testParseErrors() {
		assertParseError("1 2");
		assertParseError("1.0e0.1234");
		assertParseError("((");
		assertParseError("]");
		assertParseError("#{");
		assertParseError("#1/#2");
		assertParseError("#-3");

		assertParseError("0x0"); // not a round number of hex digits
		assertParseError("0xgg"); // not a valid hex digit, GG parser
	}
	
	private void assertParseError(String s) {
		assertThrows(ParseException.class,()->read(s));
	}
	
	/**
	 * Most CVM value representations should round trip correctly through the Reader. Test these here
	 */
	@Test public void roundTripTests() {
		// Primitives
		doRoundTripTest("1");
		doRoundTripTest("#666");
		doRoundTripTest("nil");
		doRoundTripTest("true");
		doRoundTripTest("false");
		doRoundTripTest("1.0E30");
		doRoundTripTest("0.001"); // Minimum Java double printed normally
		doRoundTripTest("9999999.0"); 
		doRoundTripTest("9999999.999999998"); // Maximum Java double printed normally
		
		// small Blobs in canonical format
		doRoundTripTest("0x");
		doRoundTripTest("0xff");
		doRoundTripTest("0x0123456789abcdef");
		
		// Syntax Objects
		doRoundTripTest("^{:foo :bar} [:a nil 3]");
		doRoundTripTest("^{} nil");
		
		// Data structures
		doRoundTripTest("[]");
		doRoundTripTest("{}");
		doRoundTripTest("{5 6,7 8,3 4,1 2}"); // Note hash order, entry delimiters
		doRoundTripTest("#{5,7,3,1}");
		doRoundTripTest("[1 :foo #{} \\a 0x 9.0 100.0 ##Inf]");
		doRoundTripTest("()");
		
		// Symbolic names
		doRoundTripTest(":foo");
		doRoundTripTest("bar");
		
		// Strings and characters
		doRoundTripTest("\\newline");
		doRoundTripTest("\\a");
		doRoundTripTest("\\\u1234"); // Unicode UTF-16 format
		doRoundTripTest("\"abc\"");
		doRoundTripTest("\"\""); // empty CVM string
		
		// Code forms
		doRoundTripTest("(fn [x] (* x x))");
	}
	
	private void doRoundTripTest(String s) {
		ACell a=Reader.read(s);
		if (a!=null) {
			AString ps=a.print();
			assertEquals(s,ps.toString());
			assertEquals(Strings.create(s),ps);
		} else {
			assertEquals("nil",s);
		}
		
		ObjectsTest.doAnyValueTests(a);
	}
	
	/**
	 * Some Strings should read correctly, but print differently. Test these here
	 */
	@Test public void differentPrintTests() {
		// Conversions to computerised scientific notation as specified in Java spec Double.toString()
		doDifferentPrintTests("0.0001","1.0E-4"); // less than 1e-3 -> scientific
		doDifferentPrintTests("0.000999999999999","9.99999999999E-4"); 
		doDifferentPrintTests("10000000.0","1.0E7"); // greater than or equal to 1e7 -> scientific
		doDifferentPrintTests("-99999999.0","-9.9999999E7");
		doDifferentPrintTests("1.333333333333333333333333","1.3333333333333333"); // Rounding to nearest unique double
		
		// Re-ordering of entries in hashmaps / sets
		doDifferentPrintTests("#{1 3 5 7}","#{5,7,3,1}");
		doDifferentPrintTests("{1 2 03 4 5 6 7 8}","{5 6,7 8,3 4,1 2}");
		
		// superfluous numerical digits are excluded
		doDifferentPrintTests("001","1");
		doDifferentPrintTests("00.1","0.1");
		doDifferentPrintTests("01.00","1.0");
		doDifferentPrintTests("#0001","#1");
		
		// normalisation of formatting
		doDifferentPrintTests("(fn[] )","(fn [])");
		doDifferentPrintTests("[1():foo,bar]","[1 () :foo bar]"); 
		
		// Path lookups
		doDifferentPrintTests("#1/foo","(lookup #1 foo)");
		doDifferentPrintTests("#1/foo/bar","(lookup (lookup #1 foo) bar)");
		
		// Non-canonical hex capitalisation
		doDifferentPrintTests("0xCaFeBaBe","0xcafebabe");
		
		// Quoting etc.
		doDifferentPrintTests("'foo","(quote foo)");
		doDifferentPrintTests("~foo","(unquote foo)");
		doDifferentPrintTests("~@(foo)","(unquote-splicing (foo))");
		doDifferentPrintTests("`[foo bar]","(quasiquote [foo bar])");
		
		// Syntax Objects
		doDifferentPrintTests("^{} [ ]","^{} []");
	}

	private void doDifferentPrintTests(String src, String dst) {
		ACell a=Reader.read(src);
		ACell b=Reader.read(dst);
		assertEquals(a,b);
		assertEquals(dst,a.print().toString());
		
		doRoundTripTest(dst); // final value should round trip normally
	}



}
