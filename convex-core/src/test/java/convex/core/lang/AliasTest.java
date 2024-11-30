package convex.core.lang;

import static convex.test.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Address;
import convex.core.cvm.Context;

public class AliasTest extends ACVMTest {
	
	@Test public void testLibraryAlias() {
		Context ctx=step("(def lib (deploy '(do (def foo 100) (defn bar [] (inc foo)) (defn baz [f] (f foo)))))");
		Address libAddress=eval(ctx,"lib");
		assertNotNull(libAddress);
		
		// no alias should exist yet
		assertUndeclaredError(step(ctx,"foo"));
		assertUndeclaredError(step(ctx,"mylib/foo"));
		
		ctx=step(ctx,"(def mylib lib)");
		
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

	@Test
	public void testImport() {
		Context ctx = step("(def lib (deploy '(def foo 100)))");
		Address libAddress = eval(ctx, "lib");
		assertNotNull(libAddress);

		// no alias should exist yet
		assertUndeclaredError(step(ctx, "foo"));
		assertUndeclaredError(step(ctx, "mylib/foo"));

		ctx = step(ctx, "(import ~lib :as mylib)");

		// Alias should now work
		assertEquals(100L, evalL(ctx, "mylib/foo"));
	}
	
	@Test
	public void testBadImports() {
		Context ctx = step("(def lib (deploy `(def foo 100)))");
		Address lib = (Address) ctx.getResult();
		assertNotNull(lib);
		
		assertArityError(step(ctx,"(import)"));
		assertArityError(step(ctx,"(import ~lib :as)"));
		assertArityError(step(ctx,"(import ~lib :as foo bar)"));
		
		// check for bad keyword
		assertSyntaxError(step(ctx,"(import ~lib :blazzzz mylib)"));
		
		// can't have bad alias
		assertSyntaxError(step(ctx,"(import ~lib :as nil)"));
		
		// can't have non-address first argument
		assertCastError(step(ctx,"(import :foo :as mylib)"));
	}
	
	@Test
	public void testTransitiveImports() {
		// create first library
		Context ctx = step("(def lib1 (deploy '(do (def foo 101))))");
		Address lib1 = (Address) ctx.getResult();
		assertNotNull(lib1);
		
		ctx = step(ctx,"(def lib2 (deploy '(do (import 0x"+lib1.toHexString()+" :as lib1) (def foo (inc lib1/foo)))))");
		Address lib2 = (Address) ctx.getResult();
		assertNotNull(lib2);

		ctx=step(ctx,"(do (import 0x"+lib1.toHexString()+" :as mylib1) (import 0x"+lib2.toHexString()+" :as mylib2))");
		
		assertEquals(101,evalL(ctx,"mylib1/foo"));
		assertEquals(102,evalL(ctx,"mylib2/foo"));
		assertUndeclaredError(step(ctx,"foo"));
		assertUndeclaredError(step(ctx,"mylib1/baddy"));
	}
	
	@Test
	public void testLibraryAssumptions() {
		Context ctx = step("(def lib (deploy '(defn run [code] (eval code))))");
		Address lib = (Address) ctx.getResult();
		ctx=step(ctx,"(do (import 0x"+lib.toHexString()+" :as lib))");
		
		// context setup should not change
		assertEquals(ctx.getAddress(),eval(ctx,"(lib/run '*address*)"));
		assertEquals(ctx.getOrigin(),eval(ctx,"(lib/run '*origin*)"));
		assertEquals(ctx.getCaller(),eval(ctx,"(lib/run '*caller*)"));
		assertEquals(ctx.getState(),eval(ctx,"(lib/run '*state*)"));
		
		// library def should define values in the current user's environment.
		assertEquals(1337L,evalL(ctx,"(do (lib/run '(def x 1337)) x)"));

		// shouldn't be possible by default to call on library code. Could be dangerous!
		assertStateError(step(ctx,"(call lib (run 1))"));
	}
}
