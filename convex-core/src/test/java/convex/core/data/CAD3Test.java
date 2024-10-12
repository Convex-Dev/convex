package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class CAD3Test {
	
	@Test public void testExtensionValues() {
		ExtensionValue ev=ExtensionValue.create((byte) 0xe3,100);
		assertEquals(100,ev.longValue());
		assertEquals(Tag.EXTENSION_VALUE_BASE+3,ev.getTag());
		assertEquals("#[e364]",ev.toString());
		
		ObjectsTest.doAnyValueTests(ev);
	}

}
