package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.CVMLong;
import convex.core.lang.Core;
import convex.core.lang.Reader;
import convex.core.util.Utils;

public class CAD3Test {
	
	@Test public void testExtensionValues() {
		ExtensionValue ev=ExtensionValue.create((byte) 0xe3,100);
		assertEquals(100,ev.longValue());
		assertEquals(Tag.EXTENSION_VALUE_BASE+3,ev.getTag());
		assertEquals("#[e364]",ev.toString());
		
		ObjectsTest.doAnyValueTests(ev);
	}
	
	@Test public void testExtensionCoreDefs() {
		assertSame(Core.VECTOR,Reader.read("#["+Utils.toHexString(Tag.CORE_DEF)+"01]"));
	}
	
	@Test public void testReadEncodings() {
		assertSame(Address.ZERO,Reader.read("#[2100]"));
		assertSame(CVMLong.ZERO,Reader.read("#[10]"));
		assertNull(Reader.read("#[00]"));
		assertEquals(ExtensionValue.create((byte) 0xe5, 0),Reader.read("#[e500]"));
	}

}
