package convex.core.lang;

import static convex.test.Assertions.assertCVMEquals;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Syntax;
import convex.core.data.ObjectsTest;

public class SyntaxTest {

	@Test
	public void testSyntaxConstructor() {
		Syntax s = Syntax.create(RT.cvm(1L));
		assertCVMEquals(1L, s.getValue());

		ObjectsTest.doAnyValueTests(s);
	}
}
