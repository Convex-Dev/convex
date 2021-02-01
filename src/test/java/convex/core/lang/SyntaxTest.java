package convex.core.lang;

import static convex.test.Assertions.assertCVMEquals;

import org.junit.jupiter.api.Test;

import convex.core.data.ObjectsTest;
import convex.core.data.Syntax;

public class SyntaxTest {

	@Test
	public void testSyntaxConstructor() {
		Syntax s = Syntax.create(RT.cvm(1L));
		assertCVMEquals(1L, s.getValue());

		ObjectsTest.doCellTests(s);
	}
}
