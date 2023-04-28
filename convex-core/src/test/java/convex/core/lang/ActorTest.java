package convex.core.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import static convex.test.Assertions.*;

public class ActorTest extends ACVMTest {

	@Test 
	public void testScopedCall() {
		Context<?> c=context();
		
		c=step(c,"(def a1 (deploy '(defn ^:callable? check [] [*address* :s *scope*])))");
		assertNotError(c);
		
		assertEquals(eval(c,"[a1 :s :foo]"),eval(c,"(call [a1 :foo] (check))"));
		assertEquals(eval(c,"[a1 :s nil]"),eval(c,"(call a1 (check))"));
		assertNull(eval(c,"(do (call [a1 :foo] (check)) *scope*)"));
	}
}
