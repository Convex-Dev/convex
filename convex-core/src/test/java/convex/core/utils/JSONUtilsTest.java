package convex.core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.Test;

import convex.core.data.Vectors;
import convex.core.util.JSONUtils;

public class JSONUtilsTest {

	@Test public void testPrint() {
		assertEquals("null",JSONUtils.toString(null));
		
		assertEquals("[]",JSONUtils.toString(Vectors.empty()));
	}
	
}
