package convex.core.data.prim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class LongTest {

	@Test
	public void testEquality() {
		long v=666666;
		assertEquals(CVMLong.create(v),CVMLong.create(v));
	}
}
