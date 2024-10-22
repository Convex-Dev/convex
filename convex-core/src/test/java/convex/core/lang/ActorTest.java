package convex.core.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import convex.core.Coin;
import convex.core.cvm.Context;
import convex.core.data.Address;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;

import static convex.test.Assertions.*;

public class ActorTest extends ACVMTest {

	@Test 
	public void testScopedCall() {
		Context c=context();
		
		c=exec(c,"(def a1 (deploy '(defn ^:callable check [] [*address* :s *scope*])))");
		
		assertEquals(eval(c,"[a1 :s :foo]"),eval(c,"(call [a1 :foo] (check))"));
		assertEquals(eval(c,"[a1 :s nil]"),eval(c,"(call a1 (check))"));
		assertNull(eval(c,"(do (call [a1 :foo] (check)) *scope*)"));
		
		c=step(c,"(def a2 (deploy '(defn ^:callable nest [t] [*scope* (call t (check)) *scope*])))");
		assertNotError(c);
		
		assertEquals(eval(c,"[nil [a1 :s :foo] nil]"),eval(c,"(call a2 (nest [a1 :foo]))"));
		assertEquals(eval(c,"[nil [a1 :s nil] nil]"),eval(c,"(call a2 (nest [a1 nil]))"));
		assertEquals(eval(c,"[nil [a1 :s nil] nil]"),eval(c,"(call a2 (nest a1))"));
		assertEquals(eval(c,"[:foo [a1 :s nil] :foo]"),eval(c,"(call [a2 :foo] (nest a1))"));
		assertEquals(eval(c,"[:foo [a1 :s :bar] :foo]"),eval(c,"(call [a2 :foo] (nest [a1 :bar]))"));
	}
	
	@Test
	public void testEnvironment() {
		Context c=context();
		c=exec(c,"(def A (deploy `(set-controller ~*address*)))");
		
		Address A=c.getResult();
		assertNotNull(A);
		
		assertSame(Maps.empty(),eval(c,"(eval-as A '*env*)"));
	}
	
	@Test 
	public void testBadScopedCalls() {
		Context c=context();
		
		c=step(c,"(def a1 (deploy '(defn ^:callable check [] [*caller* *address* *scope*])))");
		assertNotError(c);
		
		// scope vector too small
		assertCastError(step(c,"(call [] (check))"));
		assertCastError(step(c,"(call [a1] (check))"));
		
		// scope vector too big
		assertCastError(step(c,"(call [a1 nil nil] (check))"));
	}
	
	@Test 
	public void testActorAccept() {
		Context c=context();
		
		c=step(c,"(def a1 (deploy '(defn ^:callable do [c] (eval c))))");
		assertNotError(c);
		
		assertEquals(CVMLong.ZERO,eval(c,"(call a1 (do '(accept 0)))"));
		
		// Can't accept if no offer
		assertStateError(step(c,"(call a1 (do '(accept 1)))"));
		
		long bal=c.getBalance();
		{
			// Accepting less than full offer
			Context c2=step(c,"(call a1 10 (do '(accept 7)))");
			assertEquals(bal-7,c2.getBalance());
			assertEquals(Coin.MAX_SUPPLY,c2.getState().computeTotalBalance());
		}
		
		{
			// Accepting more than full offer
			Context c2=step(c,"(call a1 10 (do '(accept 17)))");
			assertStateError(c2);
			assertEquals(bal,c2.getBalance());
			assertEquals(Coin.MAX_SUPPLY,c2.getState().computeTotalBalance());
		}
		
		{
			// Accepting offer then rolling back reverses acceptance
			Context c2=step(c,"(call a1 10 (do '(do (accept 8) (rollback :done))))");
			assertNotError(c2);
			assertEquals(bal,c2.getBalance());
			assertEquals(Coin.MAX_SUPPLY,c2.getState().computeTotalBalance());
		}

	}
}
