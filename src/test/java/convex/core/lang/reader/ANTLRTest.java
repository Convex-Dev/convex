package convex.core.lang.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.Blob;
import convex.core.data.Keywords;
import convex.core.data.Lists;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.Syntax;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMChar;
import convex.core.data.prim.CVMLong;
import convex.core.lang.Symbols;

public class ANTLRTest {
	
	@SuppressWarnings("unchecked")
	private <R extends ACell> R read(String s) {
		return (R) AntlrReader.read(s);
	}

	@Test public void testParser() {
		assertNull(read("nil"));
		
		assertSame(CVMBool.TRUE,read("true"));
		assertSame(CVMBool.FALSE,read("false"));
		assertEquals(CVMLong.create(17),read("+17"));
		assertEquals(CVMLong.ZERO,read("0"));
		
		// basic data structures
		assertEquals(Vectors.of(1,2),read("[1 2]"));
		assertEquals(Lists.of(1,2),read("(1 2)"));
		assertEquals(Sets.of(1,2),read("#{1 2}"));
		assertEquals(Maps.of(1,2),read("{1 2}"));
		assertSame(Sets.empty(),read("#{}"));
		assertSame(Lists.empty(),read("()"));
		assertSame(Vectors.empty(),read("[]"));
		assertSame(Maps.empty(),read("{}"));
		
		// Keywords and Symbols
		assertEquals(Keywords.FOO,read(":foo"));
		assertEquals(Symbols.FOO,read("foo"));
		
		// Blobs
		assertEquals(Blob.EMPTY,read("0x"));
		assertEquals(Blob.fromHex("cafebabe"),read("0xcaFEBAbe"));
		
		// Address
		assertEquals(Address.create(17),read("#17"));
		

	}
	
	@Test public void testSytnax() {
		assertEquals(Syntax.create(CVMLong.ONE,Maps.empty()), read("^{} 1"));
	}
	

	@Test public void testChars() {
		assertEquals(CVMChar.create('a'), read("\\a"));
		assertEquals(CVMChar.create('\t'), read("\\tab"));
	}


}
