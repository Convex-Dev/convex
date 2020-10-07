package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

public class StringsTest {

	
	@Test public void testStringShort() {
		String t="Test";
		StringShort ss=StringShort.create(t);
		
		assertEquals(t.length(),ss.toString().length());
		assertEquals(t,ss.toString());
	}
	
	@Test public void testTreeShift() {
		assertEquals(10,StringTree.calcShift(1025));
		assertEquals(10,StringTree.calcShift(4096));
		assertEquals(10,StringTree.calcShift(16384));
		assertEquals(14,StringTree.calcShift(16385));
		assertEquals(14,StringTree.calcShift(99999));
		assertEquals(14,StringTree.calcShift(262144));
		assertEquals(18,StringTree.calcShift(262145));
		
		assertThrows(IllegalArgumentException.class,()->StringTree.calcShift(0));
		assertThrows(IllegalArgumentException.class,()->StringTree.calcShift(1024));
	}
}
