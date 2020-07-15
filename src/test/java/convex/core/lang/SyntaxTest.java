package convex.core.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.data.ObjectsTest;
import convex.core.data.Syntax;

public class SyntaxTest {

	@Test
	public void testSyntaxConstructor() {
		Syntax s = Syntax.create(1);
		assertEquals(1, (int) s.getValue());

		ObjectsTest.doCellTests(s);
	}
}
