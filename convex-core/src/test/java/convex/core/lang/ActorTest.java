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
		
		c=step(c,"(def a2 (deploy '(defn ^:callable? nest [t] [*scope* (call t (check)) *scope*])))");
		assertNotError(c);
		
		assertEquals(eval(c,"[nil [a1 :s :foo] nil]"),eval(c,"(call a2 (nest [a1 :foo]))"));
		assertEquals(eval(c,"[nil [a1 :s nil] nil]"),eval(c,"(call a2 (nest [a1 nil]))"));
		assertEquals(eval(c,"[nil [a1 :s nil] nil]"),eval(c,"(call a2 (nest a1))"));
		assertEquals(eval(c,"[:foo [a1 :s nil] :foo]"),eval(c,"(call [a2 :foo] (nest a1))"));
		assertEquals(eval(c,"[:foo [a1 :s :bar] :foo]"),eval(c,"(call [a2 :foo] (nest [a1 :bar]))"));
	}
	
	@Test 
	public void testBadScopedCalls() {
		Context<?> c=context();
		
		c=step(c,"(def a1 (deploy '(defn ^:callable? check [] [*caller* *address* *scope*])))");
		assertNotError(c);
		
		// scope vector too small
		assertCastError(step(c,"(call [] (check))"));
		assertCastError(step(c,"(call [a1] (check))"));
		
		// scope vector too big
		assertCastError(step(c,"(call [a1 nil nil] (check))"));
	}
}
