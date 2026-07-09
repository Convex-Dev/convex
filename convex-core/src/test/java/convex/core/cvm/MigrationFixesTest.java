package convex.core.cvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
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
	public void testGensym() {
		// gensym is NOT part of the genesis environment; the v1 upgrade installs it
		assertTrue(evalErrors(GENESIS, "(gensym)"));

		// Fresh Symbol with the default prefix, and with Symbol / Keyword / String prefixes
		ACell g = eval(UPGRADED, "(gensym)");
		assertTrue(g instanceof Symbol);
		assertTrue(((Symbol) g).getName().toString().startsWith("g__"));
		assertTrue(((Symbol) eval(UPGRADED, "(gensym 'n)")).getName().toString().startsWith("n__"));
		assertTrue(((Symbol) eval(UPGRADED, "(gensym :k)")).getName().toString().startsWith("k__"));
		assertTrue(((Symbol) eval(UPGRADED, "(gensym \"s\")")).getName().toString().startsWith("s__"));

		// Successive gensyms are always distinct: juice is charged before the counter
		// is read, so the observed value is strictly monotonic within a transaction
		convex.core.data.AVector<?> pair = (convex.core.data.AVector<?>) eval(UPGRADED, "[(gensym) (gensym)]");
		assertNotEquals(pair.get(0), pair.get(1));
		assertEquals(convex.core.data.prim.CVMBool.TRUE, eval(UPGRADED, "(not (= (gensym 'n) (gensym 'n)))"));

		// Deterministic: identical code on the same state yields identical symbols
		assertEquals(eval(UPGRADED, "[(gensym) (gensym 'x)]"), eval(UPGRADED, "[(gensym) (gensym 'x)]"));

		// Usable as a hygienic binding introduced into a constructed form
		assertEquals(CVMLong.create(7), eval(UPGRADED, "(let [v (gensym 'v)] (eval `(let [~v 7] ~v)))"));

		// Error cases: bad arity, non-named prefix, prefix too long for a Symbol name
		assertTrue(evalErrors(UPGRADED, "(gensym 'a 'b)"));
		assertTrue(evalErrors(UPGRADED, "(gensym 42)"));
		assertTrue(evalErrors(UPGRADED, "(gensym \"" + "a".repeat(128) + "\")"));
	}

	@Test
	public void testCat() {
		// cat is NOT part of the genesis environment; the v1 upgrade installs it
		assertTrue(evalErrors(GENESIS, "(cat 0x01 0x02)"));

		// Blob concatenation (byte-family first arg -> Blob result)
		assertEquals(eval(UPGRADED, "0x010203"), eval(UPGRADED, "(cat 0x01 0x0203)"));

		// String concatenation (char-family first arg -> String result)
		assertEquals(eval(UPGRADED, "\"foobar\""), eval(UPGRADED, "(cat \"foo\" \"bar\")"));

		// First-arg family dispatch: Keyword/Symbol are char-family and contribute
		// their NAME bytes only (no leading colon), producing a String — not a Keyword
		assertEquals(eval(UPGRADED, "\"foobar\""), eval(UPGRADED, "(cat :foo :bar)"));
		assertNotEquals(eval(UPGRADED, ":foobar"), eval(UPGRADED, "(cat :foo :bar)"));
		assertEquals(eval(UPGRADED, "\"hello-world\""), eval(UPGRADED, "(cat :hello \"-\" :world)"));

		// Raw bytes, never a cast: a String contributes UTF-8, unlike (blob "cafe")
		// which hex-parses. This divergence from blob is deliberate.
		assertEquals(eval(UPGRADED, "0x0063616665"), eval(UPGRADED, "(cat 0x00 \"cafe\")"));
		assertEquals(eval(UPGRADED, "0x63616665"), eval(UPGRADED, "(cat 0x \"cafe\")"));
		assertNotEquals(eval(UPGRADED, "(blob \"cafe\")"), eval(UPGRADED, "(cat 0x \"cafe\")"));

		// Fixed-width BlobLike first arg (Address) widens to Blob
		assertEquals(eval(UPGRADED, "0x000000000000000800"), eval(UPGRADED, "(cat #8 0x00)"));

		// Arity 0 -> empty Blob; arity 1 -> arg in its family's growable form
		assertEquals(eval(UPGRADED, "0x"), eval(UPGRADED, "(cat)"));
		assertEquals(eval(UPGRADED, "0x01"), eval(UPGRADED, "(cat 0x01)"));
		assertEquals(eval(UPGRADED, "\"ab\""), eval(UPGRADED, "(cat \"ab\")"));
		assertEquals(eval(UPGRADED, "\"foo\""), eval(UPGRADED, "(cat :foo)"));

		// Characters contribute their UTF-8 bytes, in both char and blob context
		assertEquals(eval(UPGRADED, "\"ab\""), eval(UPGRADED, "(cat \"a\" (char 98))"));   // char in String
		assertEquals(eval(UPGRADED, "\"ab\""), eval(UPGRADED, "(cat (char 97) (char 98))")); // chars -> String
		assertEquals(eval(UPGRADED, "0x0041"), eval(UPGRADED, "(cat 0x00 (char 65))"));     // char in Blob
		assertEquals(eval(UPGRADED, "0xe282ac"), eval(UPGRADED, "(cat 0x (char 0xe282ac))")); // multi-byte UTF-8 (euro)

		// nil arguments are skipped; family follows the first non-nil argument
		assertEquals(eval(UPGRADED, "0x"), eval(UPGRADED, "(cat)"));
		assertEquals(eval(UPGRADED, "0x"), eval(UPGRADED, "(cat nil nil)"));
		assertEquals(eval(UPGRADED, "0x0011"), eval(UPGRADED, "(cat 0x00 nil 0x11)"));
		assertEquals(eval(UPGRADED, "\"abc\""), eval(UPGRADED, "(cat nil \"abc\")")); // String family
		assertEquals(eval(UPGRADED, "0xab"), eval(UPGRADED, "(cat nil 0xab)"));

		// Non-nil, non-BlobLike arguments are a :CAST error — cat never casts (no Integers)
		assertTrue(evalErrors(UPGRADED, "(cat 0x00 5)"));
		assertTrue(evalErrors(UPGRADED, "(cat 123)"));
		assertTrue(evalErrors(UPGRADED, "(cat 0x00 [1 2])"));
	}

	@Test
	public void testSplice() {
		// splice is NOT part of the genesis environment; the v1 upgrade installs it
		assertTrue(evalErrors(GENESIS, "(splice 0x0000 0 0xff)"));

		// In-place overwrite (result same length as dst)
		assertEquals(eval(UPGRADED, "0x00ffff00"), eval(UPGRADED, "(splice 0x00000000 1 0xffff)"));
		assertEquals(eval(UPGRADED, "0xabcd"), eval(UPGRADED, "(splice 0x1234 0 0xabcd)"));

		// Single-byte write
		assertEquals(eval(UPGRADED, "0x00ff00"), eval(UPGRADED, "(splice 0x000000 1 0xff)"));

		// Extension: the write may run past the end, growing the result
		assertEquals(eval(UPGRADED, "0x0011223344"), eval(UPGRADED, "(splice 0x0000 1 0x11223344)"));
		// offset == (count dst) is a pure append
		assertEquals(eval(UPGRADED, "0x1234abcd"), eval(UPGRADED, "(splice 0x1234 2 0xabcd)"));
		// empty src is a no-op; offset 0 into empty dst yields src
		assertEquals(eval(UPGRADED, "0x1234"), eval(UPGRADED, "(splice 0x1234 1 0x)"));
		assertEquals(eval(UPGRADED, "0xabcd"), eval(UPGRADED, "(splice 0x 0 0xabcd)"));

		// Result family follows dst: String dst -> String, raw bytes for src (no cast)
		assertEquals(eval(UPGRADED, "\"hello there\""), eval(UPGRADED, "(splice \"hello world\" 6 \"there\")"));
		assertEquals(eval(UPGRADED, "\"foXbar\""), eval(UPGRADED, "(splice \"foobar\" 2 0x58)")); // 0x58 = 'X'

		// Byte offsets: an Address dst (byte-family) overwrites its raw bytes -> Blob
		assertEquals(eval(UPGRADED, "0x0000000000000042"), eval(UPGRADED, "(splice #8 7 0x42)"));

		// :BOUNDS when offset is negative or beyond the end of dst
		assertTrue(evalErrors(UPGRADED, "(splice 0x0000 5 0xff)"));
		assertTrue(evalErrors(UPGRADED, "(splice 0x0000 -1 0xff)"));

		// Characters contribute their UTF-8 bytes as src (byte offsets)
		assertEquals(eval(UPGRADED, "\"aXc\""), eval(UPGRADED, "(splice \"abc\" 1 (char 88))")); // 88 = 'X'
		assertEquals(eval(UPGRADED, "0x0041"), eval(UPGRADED, "(splice 0x0000 1 (char 65))"));

		// nil is treated as empty: nil src is a no-op, nil dst is an empty Blob
		assertEquals(eval(UPGRADED, "0x1234"), eval(UPGRADED, "(splice 0x1234 1 nil)"));
		assertEquals(eval(UPGRADED, "0xabcd"), eval(UPGRADED, "(splice nil 0 0xabcd)"));
		assertTrue(evalErrors(UPGRADED, "(splice nil 1 0xff)")); // offset 1 beyond empty dst

		// :CAST for non-nil non-BlobLike dst/src, or a non-Long offset (offset is not skipped)
		assertTrue(evalErrors(UPGRADED, "(splice 123 0 0xff)"));
		assertTrue(evalErrors(UPGRADED, "(splice 0x0000 0 5)"));
		assertTrue(evalErrors(UPGRADED, "(splice 0x0000 :x 0xff)"));
		assertTrue(evalErrors(UPGRADED, "(splice 0x0000 nil 0xff)"));

		// Arity is exactly 3
		assertTrue(evalErrors(UPGRADED, "(splice 0x0000 0)"));
		assertTrue(evalErrors(UPGRADED, "(splice 0x0000 0 0xff 0xff)"));

		// Logical consistency: splice is exactly the cat+slice composition
		// (splice dst off src) == (cat (slice dst 0 off) src (slice dst (min (+ off (count src)) (count dst)) (count dst)))
		String[][] cases = {
				{"0x00112233445566", "3", "0xaabb"},   // interior overwrite
				{"0x00112233", "2", "0xaabbccdd"},     // straddle end (extend)
				{"0x1234", "2", "0xabcd"},             // append at end
				{"\"hello world\"", "6", "\"there!\""},// String, extend
				{"\"abcdef\"", "0", "0x58"},           // String, single-byte interior
		};
		for (String[] c : cases) {
			String dst = c[0], off = c[1], src = c[2];
			String viaCat = "(cat (slice " + dst + " 0 " + off + ") " + src
					+ " (slice " + dst + " (min (+ " + off + " (count " + src + ")) (count " + dst + ")) (count " + dst + ")))";
			assertEquals(eval(UPGRADED, viaCat), eval(UPGRADED, "(splice " + dst + " " + off + " " + src + ")"),
					"splice/cat+slice divergence for " + java.util.Arrays.toString(c));
		}
	}

	@Test
	public void testAssetOwnsFix() {
		// #621: (owns? owner <map>) always returned true (misplaced paren discarded the
		// map branch). Set up a token the caller owns 1,000,000 of, then ask about a
		// quantity it does NOT own — buggy on genesis (true), correct on the upgraded state.
		String expr = "(do (import convex.asset :as asset) (import convex.fungible :as fungible) "
				+ "(def token (deploy (fungible/build-token {:supply 1000000}))) "
				+ "(asset/owns? *address* {token 2000000}))";
		assertEquals(convex.core.data.prim.CVMBool.TRUE, eval(GENESIS, expr));   // buggy: always true
		assertEquals(convex.core.data.prim.CVMBool.FALSE, eval(UPGRADED, expr)); // fixed: owns 1M < 2M
		// The vector form (unaffected by the fix) works on both
		String vec = "(do (import convex.asset :as asset) (import convex.fungible :as fungible) "
				+ "(def token (deploy (fungible/build-token {:supply 1000000}))) "
				+ "(asset/owns? *address* [token 1000]))";
		assertEquals(convex.core.data.prim.CVMBool.TRUE, eval(UPGRADED, vec));
	}

	@Test
	public void testMultiTokenOfferFix() {
		// #620: offering a token for which the caller had no holding record replaced the
		// caller's ENTIRE holdings map, destroying other tokens. Mint token AAA, then offer
		// (unheld) token BBB, then check AAA balance — wiped on genesis, preserved upgraded.
		String expr = "(do (import asset.multi-token :as mt) (import convex.asset :as asset) "
				+ "(call mt (create :AAA)) (call [mt :AAA] (mint 1000)) "
				+ "(call mt (create :BBB)) (asset/offer *address* [mt :BBB] 500) "
				+ "(asset/balance [mt :AAA]))";
		assertEquals(CVMLong.create(0), eval(GENESIS, expr));     // buggy: AAA holding clobbered
		assertEquals(CVMLong.create(1000), eval(UPGRADED, expr)); // fixed: AAA preserved
	}

	@Test
	public void testBoxNonFungibleFix() {
		// #622: transferring a non-fungible asset into a box failed because the asset
		// actor's `offer` keyed by the raw (scoped) receiver, not the bare box address
		// that accept looks up. Broken on genesis (:STATE), works on the upgraded state.
		String insertNft = "(do (import asset.box :as box) (import asset.nft.simple :as nft) (import convex.asset :as asset) "
				+ "(def b (box/create)) (def n (call nft (create))) (box/insert b [nft #{n}]))";
		assertTrue(evalErrors(GENESIS, insertNft));   // NFT-into-box fails
		assertFalse(evalErrors(UPGRADED, insertNft)); // fixed

		// get-offer SPI was absent on these actors: asset/get-offer errored on genesis,
		// returns the zero quantity on the upgraded state
		String getOffer = "(do (import asset.nft.simple :as nft) (import convex.asset :as asset) "
				+ "(asset/get-offer nft *address* *address*))";
		assertTrue(evalErrors(GENESIS, getOffer));
		assertFalse(evalErrors(UPGRADED, getOffer));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testUpgradedCoreDocs() {
		// The v1-installed bindings' docs live in v1-metadata.cvx and are NOT covered by
		// DocsTest/testCoreDefSymbols (both genesis-only). Validate them on the upgraded
		// state: each has a :doc with :description, and its examples evaluate without error.
		convex.core.cvm.AccountStatus core = UPGRADED.getAccount(convex.core.lang.Core.CORE_ADDRESS);
		for (Symbol sym : new Symbol[] { convex.core.cvm.Symbols.CAT, convex.core.cvm.Symbols.SPLICE,
				convex.core.cvm.Symbols.GENSYM }) {
			AHashMap<ACell, ACell> meta = core.getMetadata().get(sym);
			assertNotNull(meta, "no metadata for " + sym);
			AHashMap<ACell, ACell> doc = (AHashMap<ACell, ACell>) meta.get(convex.core.cvm.Keywords.DOC_META);
			assertNotNull(doc, "no :doc for " + sym);
			assertNotNull(doc.get(convex.core.cvm.Keywords.DESCRIPTION), "no :description for " + sym);
			AVector<AHashMap<ACell, ACell>> examples =
					(AVector<AHashMap<ACell, ACell>>) doc.get(convex.core.cvm.Keywords.EXAMPLES);
			if (examples != null) {
				for (AHashMap<ACell, ACell> ex : examples) {
					AString code = (AString) ex.get(convex.core.cvm.Keywords.CODE);
					if (code != null) {
						assertFalse(evalErrors(UPGRADED, code.toString()),
								"doc example errors for " + sym + ": " + code);
					}
				}
			}
		}
	}

	@Test
	public void testDotimesFix() {
		// #598 (6): dotimes cast the raw count form to int at EXPAND time, so only
		// literal counts worked. Buggy (:CAST) on genesis, fixed on the upgraded state.
		String exprCount = "(do (def a 0) (dotimes [i (+ 2 3)] (def a (+ a i))) a)";
		assertTrue(evalErrors(GENESIS, exprCount));
		assertEquals(CVMLong.create(10), eval(UPGRADED, exprCount));

		// The count expression is evaluated exactly once, at runtime
		assertEquals(CVMLong.create(1),
				eval(UPGRADED, "(do (def c 0) (dotimes [i (do (def c (inc c)) 3)] nil) c)"));

		// Hygiene: a user binding named `n` is untouched by the template's internal binding
		assertEquals(CVMLong.create(200),
				eval(UPGRADED, "(let [n 100] (do (def acc 0) (dotimes [i 2] (def acc (+ acc n))) acc))"));

		// Non-interference: literal counts behave identically on both states
		for (State s : new State[] { GENESIS, UPGRADED }) {
			assertEquals(CVMLong.create(10), eval(s, "(do (def a 0) (dotimes [i 5] (def a (+ a i))) a)"));
			// zero and negative counts execute the body zero times
			assertEquals(CVMLong.create(0), eval(s, "(do (def z 0) (dotimes [i 0] (def z 1)) (dotimes [i -2] (def z 1)) z)"));
			// nested dotimes
			assertEquals(Reader.read("[[0 0] [0 1] [1 0] [1 1]]"),
					eval(s, "(do (def v []) (dotimes [i 2] (dotimes [j 2] (def v (conj v [i j])))) v)"));
			// non-symbol loop binding is still a :CAST error
			assertTrue(evalErrors(s, "(dotimes [7 5] nil)"));
		}
	}

	@Test
	public void testMacroHygieneFix() {
		// #602: for / for-loop / switch introduced template bindings with fixed names
		// visible to the user's body, so a colliding body symbol captured them. Fixed via
		// gensym on the upgraded state; genesis keeps the capturing behaviour.

		// for: the plain loop index `i` (and accumulator `a`) are the natural capture risk.
		// Genesis body `i` captures the loop index; upgraded body `i` is the user's binding.
		assertEquals(Reader.read("[0 1 2]"),       eval(GENESIS,  "(let [i 100] (for [x [1 2 3]] i))"));
		assertEquals(Reader.read("[100 100 100]"), eval(UPGRADED, "(let [i 100] (for [x [1 2 3]] i))"));
		// accumulator capture: genesis body `a` sees the (empty) accumulator, upgraded sees :x
		assertEquals(Reader.read("[[]]"), eval(GENESIS,  "(let [a :x] (for [y [1]] a))"));
		assertEquals(Reader.read("[:x]"), eval(UPGRADED, "(let [a :x] (for [y [1]] a))"));

		// for-loop: internal `value#` binding. Genesis body captures it (nil), upgraded sees :outer
		assertNull(eval(GENESIS, "(let [value# :outer] (for-loop [i 0 (< i 1) (inc i)] value#))"));
		assertEquals(Reader.read(":outer"), eval(UPGRADED, "(let [value# :outer] (for-loop [i 0 (< i 1) (inc i)] value#))"));

		// switch: internal subject binding `v#`, referenced from constructed cond clauses.
		// Genesis body `v#` captures the subject (5), upgraded sees the user's :outer
		assertEquals(CVMLong.create(5), eval(GENESIS,  "(let [v# :outer] (switch 5 5 v#))"));
		assertEquals(Reader.read(":outer"), eval(UPGRADED, "(let [v# :outer] (switch 5 5 v#))"));

		// Non-interference: normal (non-colliding) uses behave identically before and after
		for (State s : new State[] { GENESIS, UPGRADED }) {
			assertEquals(Reader.read("[2 3 4]"), eval(s, "(for [x [1 2 3]] (inc x))"));
			assertEquals(CVMLong.create(2), eval(s, "(for-loop [i 0 (< i 3) (inc i)] i)"));
			assertNull(eval(s, "(for-loop [i 0 (< i 0) (inc i)] i)")); // zero iterations -> nil
			assertEquals(Reader.read(":two"), eval(s, "(switch (+ 1 1) 0 :zero 1 :one 2 :two :default-value)"));
			assertEquals(Reader.read(":default-value"), eval(s, "(switch 9 0 :zero :default-value)"));
			assertNull(eval(s, "(switch 9 0 :zero)")); // no match, no default -> nil
			// nested for still works (inner internals shadow outer on v0, independently
			// fresh on v1): (+ x y) over x in {1,2}, y in {1,2}
			assertEquals(Reader.read("[[2 3] [3 4]]"),
					eval(s, "(for [x [1 2]] (for [y [1 2]] (+ x y)))"));
		}
	}

	/** Core symbols whose docstrings are corrected by the v1 metadata migration (#600). */
	static final String[] META_FIXED = {
		"address", "apply", "bit-not", "call*", "comp", "concat", "create-peer", "empty",
		"get-holding", "hash", "keccak256", "sha256", "inc", "map", "merge", "reduce",
		"signum", "symbol", "get-peer-stake", "set-peer-stake"
	};

	@Test
	public void testMetadataCorrections() {
		// #600: docstring corrections applied via no-value `def` in the v1 migration.
		for (String s : META_FIXED) {
			// Invariant: ONLY :doc changed — every other metadata key (notably :static)
			// is preserved exactly. This guards the no-value-def / full-replace mechanism.
			assertEquals(eval(GENESIS,  "(dissoc (lookup-meta '" + s + ") :doc)"),
			             eval(UPGRADED, "(dissoc (lookup-meta '" + s + ") :doc)"),
			             () -> "non-:doc metadata changed for " + s);
			// The :doc genuinely changed on the upgraded state
			assertNotEquals(eval(GENESIS,  "(:doc (lookup-meta '" + s + "))"),
			                eval(UPGRADED, "(:doc (lookup-meta '" + s + "))"),
			                () -> ":doc not changed for " + s);
		}

		// Values are preserved (no-value def): the functions still work on the upgrade
		assertEquals(CVMLong.create(6), eval(UPGRADED, "(bit-not (bit-not 6))"));
		assertEquals(CVMLong.create(3), eval(UPGRADED, "(reduce + 0 [1 2])"));
		assertEquals(Reader.read("(2 3)"), eval(UPGRADED, "(map inc '(1 2))"));

		// Spot-check specific corrections landed on the upgraded state
		assertEquals(1L, ((CVMLong) eval(UPGRADED,
			"(count (:params (first (:signature (:doc (lookup-meta 'bit-not))))))")).longValue());
		assertTrue(eval(UPGRADED, "(:description (:doc (lookup-meta 'symbol)))").toString().contains("128"));
		assertFalse(eval(UPGRADED, "(str (:doc (lookup-meta 'merge)))").toString().contains("not Indexes"));

		// Genesis retains the original (buggy) docs — proving genesis is unmodified
		assertEquals(2L, ((CVMLong) eval(GENESIS,
			"(count (:params (first (:signature (:doc (lookup-meta 'bit-not))))))")).longValue());
		assertTrue(eval(GENESIS, "(:description (:doc (lookup-meta 'symbol)))").toString().contains("64"));
	}

	@Test
	public void testNewBindingMetadata() {
		// The bindings v1 introduces carry full metadata: :doc applied by the metadata
		// step, :static from the bootstrap step (wholesale-replaced, so both must hold)
		for (String s : new String[] { "schedule-upgrade", "unschedule-upgrade", "gensym" }) {
			assertNull(eval(GENESIS, "(lookup-meta '" + s + ")"), () -> s + " must not exist at genesis");
			assertEquals(convex.core.data.prim.CVMBool.TRUE,
					eval(UPGRADED, "(boolean (:description (:doc (lookup-meta '" + s + "))))"),
					() -> "no :doc description for " + s);
			assertEquals(convex.core.data.prim.CVMBool.TRUE,
					eval(UPGRADED, "(:static (lookup-meta '" + s + "))"),
					() -> ":static not preserved for " + s);
		}
		// `doc` works on the upgraded state as a user would call it
		assertFalse(evalErrors(UPGRADED, "(assert (:description (doc gensym)))"));
	}

	@Test
	public void testDocsPreserved() {
		// The migration redefines via defn with metadata, so docstrings survive
		assertFalse(evalErrors(UPGRADED, "(assert (:doc (lookup-meta 'update)))"));
		assertFalse(evalErrors(UPGRADED, "(assert (:doc (lookup-meta 'update-in)))"));
		assertFalse(evalErrors(UPGRADED, "(assert (:doc (lookup-meta 'dotimes)))"));
		assertFalse(evalErrors(UPGRADED, "(assert (:doc (lookup-meta 'for)))"));
		assertFalse(evalErrors(UPGRADED, "(assert (:doc (lookup-meta 'for-loop)))"));
		assertFalse(evalErrors(UPGRADED, "(assert (:doc (lookup-meta 'switch)))"));
	}
}
