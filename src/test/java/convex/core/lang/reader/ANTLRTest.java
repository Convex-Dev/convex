package convex.core.lang.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;

public class ANTLRTest {
	
	private ACell read(String s) {
		return AntlrReader.read(s);
	}

	@Test public void testParser() {
		
		assertSame(CVMBool.TRUE,AntlrReader.read("true"));
		assertSame(CVMBool.FALSE,AntlrReader.read("false"));
		assertEquals(CVMLong.create(17),AntlrReader.read("+17"));
		assertEquals(CVMLong.ZERO,AntlrReader.read("0"));
		
		assertEquals(Vectors.of(1,2),read("[1 2]"));
		
		assertEquals(Address.create(17),read("#17"));

	}


}
