package convex.core.lang;

import static convex.core.lang.TestState.*;
import static convex.test.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import convex.core.data.Address;
import convex.core.data.Maps;

public class AliasTest {
	@Test public void testInitialAlias() {
		assertEquals(Maps.of(null,Core.CORE_ADDRESS),eval("*aliases*"));
	}
	
	@Test public void testWipeAlias() {
		Context<?> ctx=step("(def *aliases* {})");
		assertUndeclaredError(step(ctx,"count"));
	}
	
	@Test public void testLibraryAlias() {
		Context<?> ctx=step("(def lib (deploy '(do (def foo 100) (defn bar [] (inc foo)) (defn baz [f] (f foo)))))");
		Address libAddress=eval(ctx,"lib");
		assertNotNull(libAddress);
		
		// no alias should exist yet
		assertUndeclaredError(step(ctx,"foo"));
		assertUndeclaredError(step(ctx,"mylib/foo"));
		
		ctx=step(ctx,"(def *aliases* (assoc *aliases* 'mylib lib))");
		
		// Alias should now work
		assertEquals(100L,evalL(ctx,"mylib/foo"));
		
		// Use of function with access to library namespace should work
		assertEquals(101L,evalL(ctx,"(mylib/bar)"));
		assertEquals(101L,evalL(ctx,"(let [f mylib/bar] (f))"));
		
		// Shouldn't be able to call as an actor
		assertStateError(step(ctx,"(call lib (bar))"));
		
		// should be able to pass a closure to the library
		assertEquals(10000L, evalL(ctx,"(let [f (fn [x] (* x x))] (mylib/baz f))"));
		assertEquals(99L, evalL(ctx,"(do (def f (fn [x] (dec x))) (mylib/baz f))"));
	}
}
