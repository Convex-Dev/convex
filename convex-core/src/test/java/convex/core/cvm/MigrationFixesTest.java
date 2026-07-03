package convex.core.cvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Symbol;
import convex.core.data.prim.CVMLong;
import convex.core.init.Init;
import convex.core.init.InitTest;
import convex.core.lang.Reader;

/**
 * Tests for the v1 upgrade fixes: the `update` / `update-in` variadic-arity fixes
 * (#533) and the `convex.fungible` `add-mint` unlimited-supply default (#528).
 *
 * Gated on the statically-built upgraded state ({@link InitTest#UPGRADED}): the
 * fixes are present at the latest protocol version, while genesis retains the old
 * (buggy) behaviour — proving genesis is unmodified and the migration is what
 * applies the fix. See UPGRADE.md.
 */
public class MigrationFixesTest {

	static final State GENESIS = InitTest.STATE;
	static final State UPGRADED = InitTest.UPGRADED;

	/** Evaluate code against a State, asserting success. */
	static ACell eval(State s, String code) {
		Context ctx = Context.create(s, Init.GENESIS_ADDRESS).eval(Reader.read(code));
		assertFalse(ctx.isExceptional(), () -> "unexpected error evaluating '" + code + "': " + ctx.getValue());
		return ctx.getResult();
	}

	/** Evaluate code against a State, returning true if it produced an error. */
	static boolean evalErrors(State s, String code) {
		return Context.create(s, Init.GENESIS_ADDRESS).eval(Reader.read(code)).isExceptional();
	}

	@Test
	public void testUpgradedState() {
		// Genesis is version 0; the upgraded state is at the latest version and valid
		assertEquals(0L, GENESIS.getProtocolVersion());
		assertEquals(Migrations.MAX_VERSION, UPGRADED.getProtocolVersion());
		StateTest.doStateTests(UPGRADED);
	}

	@Test
	public void testQuasiquoteSetMapFix() {
		// #598 (1): quasiquote of a set/map containing an unquote must produce the
		// set/map, not a call-form list. Buggy on genesis, fixed on the upgraded state.
		assertFalse(eval(GENESIS, "(set? (quasiquote #{1 (unquote 2)}))").equals(convex.core.data.prim.CVMBool.TRUE));
		assertEquals(convex.core.data.Sets.of(1L, 2L), eval(UPGRADED, "(quasiquote #{1 (unquote 2)})"));
		assertEquals(convex.core.data.Maps.of(2L, 2L), eval(UPGRADED, "`{~(inc 1) 2}"));
		// nested set with unquote
		assertEquals(convex.core.data.Sets.of(1L, convex.core.data.Sets.of(2L)),
				eval(UPGRADED, "(quasiquote #{1 #{(unquote 2)}})"));
		// regressions: pure (no-unquote) and vector/list unchanged on both states
		for (State s : new State[] { GENESIS, UPGRADED }) {
			assertEquals(convex.core.data.Sets.of(1L, 2L), eval(s, "`#{1 2}"));
			assertEquals(convex.core.data.Vectors.of(2L, 2L), eval(s, "`[~(inc 1) 2]"));
		}
	}

	@Test
	public void testQuasiquoteFalseFix() {
		// #598 (2): top-level `~false dropped to (unquote false) instead of false.
		// Buggy on genesis (returns the (unquote false) list), fixed on the upgraded state.
		assertNotEquals(convex.core.data.prim.CVMBool.FALSE, eval(GENESIS, "`~false"));
		assertEquals(convex.core.data.prim.CVMBool.FALSE, eval(UPGRADED, "`~false"));
		// false embedded in a list unquote resolves correctly on the upgraded state
		assertEquals(convex.core.data.prim.CVMBool.FALSE, eval(UPGRADED, "(nth `(a ~false b) 1)"));
		// regressions on both states (only the literal-false top-level case was broken)
		for (State s : new State[] { GENESIS, UPGRADED }) {
			assertEquals(convex.core.data.prim.CVMBool.TRUE, eval(s, "`~true"));
			assertNull(eval(s, "`~nil"));
			assertEquals(convex.core.data.prim.CVMBool.FALSE, eval(s, "`~(= 1 2)"));
		}
	}

	@Test
	public void testDefineDoubleEvalFix() {
		// #598 (3): define used its (eval (def ...)) return as the expansion, evaluating
		// the value twice. Broke when the value evaluates to a symbol or list.
		// A symbol value is UNDECLARED on genesis, correct on the upgraded state:
		assertTrue(evalErrors(GENESIS, "(do (define dg 'hello) dg)"));
		assertEquals(Symbol.create("hello"), eval(UPGRADED, "(do (define dg 'hello) dg)"));
		// A list value is double-evaluated on genesis; fixed returns the value once:
		assertTrue(evalErrors(GENESIS, "(do (define dl '(foo)) dl)"));
		assertEquals(Reader.read("(foo)"), eval(UPGRADED, "(do (define dl '(foo)) dl)"));

		// Non-interference: for self-evaluating values (all real genesis uses, e.g.
		// basic.cvx / archon.cvx) the result is identical before and after the upgrade.
		for (State s : new State[] { GENESIS, UPGRADED }) {
			assertEquals(CVMLong.create(0), eval(s, "(define nc 0)"));
			assertEquals(Reader.read("\"http\""), eval(s, "(define ns \"http\")"));
			assertEquals(Address.create(13), eval(s, "(do (define na (or #13 #14)) na)"));
			// 1-arity declares the symbol bound to nil on both states
			assertEquals(convex.core.data.prim.CVMBool.TRUE, eval(s, "(do (define dz) (defined? 'dz))"));
			assertNull(eval(s, "(do (define dz) dz)"));
		}
	}

	@Test
	public void testCallArityFix() {
		// #598 (4): call with too many args silently expanded to nil (no invocation,
		// no error) on genesis; it is an :ARITY error on the upgraded state.
		assertNull(eval(GENESIS, "(call #8 500 2 (foo))"));
		assertTrue(evalErrors(UPGRADED, "(call #8 500 2 (foo))"));
		// too-few already errored on both (unchanged)
		assertTrue(evalErrors(GENESIS, "(call #8)"));
		assertTrue(evalErrors(UPGRADED, "(call #8)"));
		// Non-interference: valid call forms expand identically before and after upgrade
		assertEquals(eval(GENESIS, "(expand '(call #8 (foo 1 2)))"),
				eval(UPGRADED, "(expand '(call #8 (foo 1 2)))"));
		assertEquals(eval(GENESIS, "(expand '(call #8 500 (foo 1 2)))"),
				eval(UPGRADED, "(expand '(call #8 500 (foo 1 2)))"));
	}

	@Test
	public void testUpdateVariadicFix() {
		// #533: the 5+ arg arity dropped the first extra argument.
		// (update {:a 0} :a + 1 2) => {:a (+ 0 1 2)} = {:a 3}, was {:a (+ 0 2)} = {:a 2}
		assertEquals(CVMLong.create(2), eval(GENESIS,  "(:a (update {:a 0} :a + 1 2))"));  // buggy (unchanged genesis)
		assertEquals(CVMLong.create(3), eval(UPGRADED, "(:a (update {:a 0} :a + 1 2))"));  // fixed
		assertEquals(CVMLong.create(6),  eval(UPGRADED, "(:a (update {:a 0} :a + 1 2 3))"));
		assertEquals(CVMLong.create(10), eval(UPGRADED, "(:a (update {:a 0} :a + 1 2 3 4))"));

		// Lower arities were already correct and are unchanged by the fix
		for (State s : new State[] { GENESIS, UPGRADED }) {
			assertEquals(CVMLong.create(1), eval(s, "(:a (update {:a 0} :a inc))"));  // 3-arg
			assertEquals(CVMLong.create(1), eval(s, "(:a (update {:a 0} :a + 1))"));  // 4-arg
		}
	}

	@Test
	public void testUpdateInVariadicFix() {
		// #533: update-in's variadic arity referenced an undefined `ks` (param typo)
		// AND dropped `x`. On genesis it errors; on the upgraded state it works.
		assertTrue(evalErrors(GENESIS, "(update-in {:a 0} [:a] + 1 2)"));  // UNDECLARED ks
		assertEquals(CVMLong.create(3), eval(UPGRADED, "(get-in (update-in {:a 0} [:a] + 1 2) [:a])"));
		assertEquals(CVMLong.create(6), eval(UPGRADED, "(get-in (update-in {:a 0} [:a] + 1 2 3) [:a])"));

		// Lower arities were already correct and are unchanged
		for (State s : new State[] { GENESIS, UPGRADED }) {
			assertEquals(CVMLong.create(1), eval(s, "(get-in (update-in {:a 0} [:a] inc) [:a])"));  // 3-arg
			assertEquals(CVMLong.create(1), eval(s, "(get-in (update-in {:a 0} [:a] + 1) [:a])"));  // 4-arg
		}
	}

	@Test
	public void testAddMintFix() {
		// #528: add-mint with no :max-supply should allow unlimited minting. On genesis
		// it defaulted max-supply to 0, blocking all mints; the upgraded state fixes it.
		String code = "(do (import convex.fungible :as fungible)"
				+ " (def token (deploy [(fungible/build-token {}) (fungible/add-mint {})]))"
				+ " (fungible/mint token 1000)"
				+ " (fungible/balance token *address*))";
		assertTrue(evalErrors(GENESIS, code));                      // buggy: mint blocked (cap 0)
		assertEquals(CVMLong.create(1000), eval(UPGRADED, code));   // fixed: unlimited

		// An explicit :max-supply still caps, on both states (only the default changed)
		String capped = "(do (import convex.fungible :as fungible)"
				+ " (def token (deploy [(fungible/build-token {}) (fungible/add-mint {:max-supply 500})]))"
				+ " (fungible/mint token 600))"; // exceeds cap
		assertTrue(evalErrors(UPGRADED, capped));
	}

	@Test
	public void testDocsPreserved() {
		// The migration redefines via defn with metadata, so docstrings survive
		assertFalse(evalErrors(UPGRADED, "(assert (:doc (lookup-meta 'update)))"));
		assertFalse(evalErrors(UPGRADED, "(assert (:doc (lookup-meta 'update-in)))"));
	}
}
