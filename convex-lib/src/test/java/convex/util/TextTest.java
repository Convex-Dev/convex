package convex.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import convex.core.util.Text;

public class TextTest {

	@Test
	public void testWhiteSpace() {
		checkWhiteSpace(0);
		checkWhiteSpace(10);
		checkWhiteSpace(32);
		checkWhiteSpace(33);
		checkWhiteSpace(95);
		checkWhiteSpace(96);
		checkWhiteSpace(97);
		checkWhiteSpace(100);
	}

	private void checkWhiteSpace(int len) {
		String s = Text.whiteSpace(len);
		assertEquals(len, s.length());
		assertEquals("", s.trim());
	}
}
