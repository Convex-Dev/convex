package convex.core.lang.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.Keywords;
import convex.core.data.Lists;
import convex.core.data.Sets;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;

public class ANTLRTest {
	
	private ACell read(String s) {
		return AntlrReader.read(s);
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
		assertSame(Sets.empty(),read("#{}"));
		assertSame(Lists.empty(),read("()"));
		assertSame(Vectors.empty(),read("[]"));
		
		// Keywords and Symbols
		assertEquals(Keywords.FOO,read(":foo"));
		
		// Address
		assertEquals(Address.create(17),read("#17"));
		

	}


}
