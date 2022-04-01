package convex.core.lang.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Address;
import convex.core.data.Blob;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Lists;
import convex.core.data.Maps;
import convex.core.data.ObjectsTest;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMChar;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.ParseException;
import convex.core.lang.Reader;
import convex.core.lang.Symbols;

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
		
	@Test public void testPrimitives () {
		assertSame(CVMBool.TRUE,read("true"));
		assertSame(CVMBool.FALSE,read("false"));
		assertEquals(CVMLong.create(17),read("17"));
		assertEquals(CVMLong.create(-2),read("-2"));
		assertEquals(CVMLong.ZERO,read("0"));
	}
		
	@Test public void testDataStructures() {
		// basic data structures
		assertEquals(Vectors.of(1,2),read("[1 2]"));
		assertEquals(Lists.of(1,2),read("(1 2)"));
		assertEquals(Sets.of(1,2),read("#{1 2}"));
		assertEquals(Maps.of(1,2),read("{1 2}"));
		
		// empty structures
		assertSame(Sets.empty(),read("#{}"));
		assertSame(Lists.empty(),read("()"));
		assertSame(Vectors.empty(),read("[]"));
		assertSame(Maps.empty(),read("{}"));
	}
	
	@Test public void testSymbols() {
		assertEquals(Symbols.FOO,read("foo"));
		assertEquals(Symbol.create("/"),read("/"));
	}
	
	@Test public void testKeywords() {
		assertEquals(Keywords.FOO,read(":foo"));
		assertEquals(Keyword.create("/"),read(":/"));
	}

		
	@Test public void testBlobs() {
		// Blobs
		assertEquals(Blob.EMPTY,read("0x"));
		assertEquals(Blob.fromHex("cafebabe"),read("0xcaFEBAbe"));
	}
	
	@Test public void testAddress() {
		// Address
		assertEquals(Address.create(17),read("#17"));
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
		assertEquals(Strings.create(""), read("\"\""));
		assertEquals(Strings.create("a"), read("\"a\""));
		assertEquals(Strings.create("bar"), read("\"bar\""));
		assertEquals(Strings.create("ba\nr"), read("\"ba\\\nr\""));
	}
	
	@Test public void testReadAll() {
		assertSame(Lists.empty(),readAll(""));
		assertEquals(Lists.of(1,2),readAll(" 1 2 "));
		
		assertThrows(ParseException.class,()->readAll("1 2 ("));

	}
	
	@Test public void testPath() {
		assertEquals(Lists.of(Symbols.LOOKUP,Address.ZERO,Symbols.FOO),AntlrReader.read("#0/foo"));
		assertEquals(Lists.of(Symbols.LOOKUP,Address.ZERO,Symbols.DIVIDE),AntlrReader.read("#0//"));
	}

	@Test public void testError() {
		assertThrows(ParseException.class,()->read("1 2"));
		assertThrows(ParseException.class,()->read("1.0e0.1234"));
	}
	
	@Test public void roundTripTests() {
		doRoundTripTest("1");
		doRoundTripTest("#666");
		doRoundTripTest("0xff");
		doRoundTripTest("0x0123456789abcdef");
		doRoundTripTest("nil");
		doRoundTripTest("true");
		doRoundTripTest("false");
		doRoundTripTest("[]");
		doRoundTripTest("{}");
		doRoundTripTest("{5 6,1 2,3 4,7 8}"); // Note hash order
		doRoundTripTest("#{5,1,3,7}");
		doRoundTripTest("[1 :foo #{} \\a 0x 9.0 100.0 ##Inf]");
		doRoundTripTest("()");
		doRoundTripTest(":foo");
		doRoundTripTest("bar");
		doRoundTripTest("\\newline");
		doRoundTripTest("\\a");
		doRoundTripTest("\\\u1234"); // Unicode UTF-16 format
		doRoundTripTest("\"abc\"");
	}

	private void doRoundTripTest(String s) {
		ACell a=Reader.read(s);
		if (a!=null) {
			assertTrue(a.isCanonical());
			AString ps=a.print();
			assertEquals(s,ps.toString());
			assertEquals(Strings.create(s),ps);
		} else {
			assertEquals("nil",s);
		}
		
		ObjectsTest.doAnyValueTests(a);
	}

}
