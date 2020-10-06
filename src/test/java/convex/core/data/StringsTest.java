package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class StringsTest {

	
	@Test public void testStringShort() {
		String t="Test";
		StringShort ss=StringShort.create(t);
		
		assertEquals(t.length(),ss.toString().length());
		assertEquals(t,ss.toString());
	}
}
