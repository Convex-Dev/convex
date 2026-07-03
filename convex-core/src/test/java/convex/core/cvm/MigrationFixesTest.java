package convex.core.cvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
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
