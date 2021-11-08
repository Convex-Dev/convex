package convex.core.lang;

import static convex.test.Assertions.assertArityError;
import static convex.test.Assertions.assertBoundsError;
import static convex.test.Assertions.assertCVMEquals;
import static convex.test.Assertions.assertCastError;
import static convex.test.Assertions.assertCompileError;
import static convex.test.Assertions.assertDepthError;
import static convex.test.Assertions.assertJuiceError;
import static convex.test.Assertions.assertNotError;
import static convex.test.Assertions.assertUndeclaredError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.Constants;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AList;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Keywords;
import convex.core.data.Lists;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.ParseException;
import convex.core.init.Init;
import convex.core.init.InitTest;
import convex.core.lang.ops.Constant;
import convex.core.lang.ops.Def;
import convex.core.lang.ops.Do;
import convex.core.lang.ops.Invoke;
import convex.core.lang.ops.Lambda;
import convex.core.lang.ops.Local;
import convex.core.lang.ops.Lookup;
import convex.core.util.Utils;
import convex.test.Samples;

/**
 * Tests for basic language features and compiler functionality.
 *
 * State setup includes only basic accounts and core library.
 */
public class CompilerTest extends ACVMTest {

	protected CompilerTest() {
		super(InitTest.STATE);
	}

	@Test
	public void testConstants() {
		assertEquals(1L,evalL("1"));
		assertEquals(Samples.FOO,eval(":foo"));
		assertCVMEquals('d',eval("\\d"));
		assertCVMEquals("baz",eval("\"baz\""));

		assertSame(Vectors.empty(),eval("[]"));
		assertSame(Lists.empty(),eval("()"));

		assertNull(eval("nil"));
		assertSame(CVMBool.TRUE,eval("true"));
		assertSame(CVMBool.FALSE,eval("false"));
	}

	@Test public void testDo() {
		assertEquals(1L,evalL("(do 2 1)"));
		assertEquals(1L,evalL("(do *depth*)")); // Adds one level to initial depth
		assertEquals(2L,evalL("(do (do *depth*))"));
	}

	@Test public void testMinCompileRegression() throws IOException {
		Context<?> c=context();
		String src=Utils.readResourceAsString("testsource/min.con");
		ACell form=Reader.read(src);
		Context<ACell> exp=c.expand(form);
		assertNotError(exp);
		Context<AOp<ACell>> com=c.compile(exp.getResult());

		assertNotError(com);
	}

	@Test public void testFnCasting() {
		assertEquals(1L,evalL("({2 1} 2)"));
		assertNull(eval("({2 1} 1)"));
		assertEquals(3L,evalL("({2 1} 1 3)"));
		assertCVMEquals(Boolean.TRUE,eval("(#{2 1} 1)"));
		assertCVMEquals(Boolean.TRUE,eval("(#{nil 1} nil)"));
		assertCVMEquals(Boolean.FALSE,eval("(#{2 1} 7)"));
		assertCVMEquals(Boolean.FALSE,eval("(#{2 1} nil)"));
		assertEquals(7L,evalL("([] 3 7)"));

		assertEquals(3L,evalL("(:foo {:bar 1 :foo 3})"));
		assertNull(eval("(:foo {:bar 1})"));
		assertEquals(7L,evalL("(:baz {:bar 1 :foo 3} 7)"));
		assertEquals(2L,evalL("(:foo nil 2)")); // TODO: is this sane? treat nil as empty?

		// zero arity failing
		assertArityError(step("(:foo)"));
		assertArityError(step("({})"));
		assertArityError(step("(#{})"));
		assertArityError(step("([])"));

		// non-associative lookup
		assertCastError(step("(:foo 1 2)"));

		// too much arity
		assertArityError(step("({} 1 2 3)"));
		assertBoundsError(step("([] 1)"));
		assertArityError(step("([] 1 2 3)"));
		assertArityError(step("(:foo 1 2 3)")); // arity > type
	}

	@Test public void testApply() {
		assertCVMEquals(true,eval("(apply = nil)"));
		assertCVMEquals(true,eval("(apply = [1 1])"));
		assertCVMEquals(false,eval("(apply = [1 1 nil])"));

		assertArityError(step("(apply)"));

	}

	@Test public void testLambda() {
		assertEquals(2L,evalL("((fn [a] 2) 3)"));
		assertEquals(3L,evalL("((fn [a] a) 3)"));

		assertEquals(1L,evalL("((fn [a] *depth*) 3)")); // Level of invoke depth
	}


	@Test public void testDef() {
		assertEquals(2L,evalL("(do (def a 2) (def b 3) a)"));
		assertEquals(7L,evalL("(do (def a 2) (def a 7) a)"));

		// TODO: check if these are most logical error types?
		assertCompileError(step("(def :a 1)"));
		assertCompileError(step("(def a)"));
		assertCompileError(step("(def a 2 3)"));
	}

	@Test public void testDefMetadataOnLiteral() {
		Context<?> ctx=step("(def a ^:foo 2)");
		assertNotError(ctx);
		AHashMap<ACell,ACell> m=ctx.getMetadata().get(Symbol.create("a"));
		assertSame(CVMBool.TRUE,m.get(Keywords.FOO));
	}

	@Test public void testDefMetadataOnForm() {
		String code="(def a ^:foo (+ 1 2))";
		Symbol sym=Symbol.create("a");

		Context<?> ctx=step(code);
		assertNotError(ctx);
		ACell v=ctx.getEnvironment().get(sym);
		assertCVMEquals(3L,v);
		assertSame(CVMBool.TRUE,ctx.getMetadata().get(sym).get(Keywords.FOO));
	}

	@Test public void testDefMetadataOnSymbol() {
		Context<?> ctx=step("(def ^{:foo true} a (+ 1 2))");
		assertNotError(ctx);

		Symbol sym=Symbol.create("a");
		ACell v=ctx.getEnvironment().get(sym);
		assertCVMEquals(3L,v);
		assertSame(CVMBool.TRUE,ctx.getMetadata().get(sym).get(Keywords.FOO));
	}

	@Test public void testCond() {
		assertEquals(1L,evalL("(cond nil 2 1)"));
		assertEquals(4L,evalL("(cond nil 2 false 3 4)"));
		assertEquals(2L,evalL("(cond 1 2 3 4)"));
		assertNull(eval("(cond)"));
		assertNull(eval("(cond false true)"));
	}

	@Test public void testIf() {
		assertEquals(read("(cond false 4)"),expand("(if false 4)"));
		assertNull(eval("(if false 4)"));
		assertEquals(4L,evalL("(if true 4)"));
		assertEquals(2L,evalL("(if 1 2 3)"));
		assertEquals(3L,evalL("(if nil 2 3)"));
		assertEquals(7L,evalL("(if :foo 7)"));
		assertEquals(1L,evalL("(if true *depth*)"));

		// test that if macro expansion happens correctly inside vector
		assertEquals(Vectors.of(3L,2L),eval("[(if nil 2 3) (if 1 2 3)]"));

		// test that if macro expansion happens correctly inside other macro
		assertEquals(3L,evalL("(if (if 1 nil 3) 2 3)"));

		// ARITY error if too few or too many branches
		assertArityError(step("(if :foo)"));
		assertArityError(step("(if :foo 1 2 3 4 5)"));
	}

	@Test
	public void testStackOverflow()  {
		// fake state with default juice
		Context<?> c=context();

		AOp<CVMLong> op=Do.create(
				    // define a nasty function that calls its argument recursively on itself
					Def.create("fubar",
							Lambda.create(Vectors.of(Symbol.create("func")),
											Invoke.create(Local.create(0),Local.create(0)))),
					// call the nasty function on itself
					Invoke.create(Invoke.create(Lookup.create("fubar"),Lookup.create("fubar")))
				);

		assertDepthError(c.execute(op));
	}

	@Test
	public void testMissingVar() {
		assertUndeclaredError(step("this-should-not-resolve"));
	}

	@Test
	public void testBadEval() {
		assertThrows(ParseException.class,()->eval("(("));
	}

	@Test
	public void testUnquote() {
		// Unquote used to execute code at compile time
		assertEquals(RT.cvm(3L),eval("~(+ 1 2)"));
		assertEquals(Constant.of(3L),comp("~(+ 1 2)"));

		assertEquals(RT.cvm(3L),eval("`~`~(+ 1 2)"));

		// Misc cases
		assertNull(eval("`~nil"));
		assertEquals(Keywords.STATE,eval("(let [a :state] `~a)"));
		assertEquals(Vectors.of(1L,3L),eval("`[1 ~(+ 1 2)]"));
		assertEquals(Lists.of(Symbols.INC,3L),eval("`(inc ~(+ 1 2))"));
		assertUndeclaredError(step("`~undefined-1"));
		assertUndeclaredError(step("~'undefined-1"));

		// not we require compilation down to a single constant
		assertEquals(Constant.of(7L),comp("~(+ 7)"));

		assertArityError(step("~(inc)"));
		assertCastError(step("~(inc :foo)"));

		// TODO: what are right error types here?
		assertCompileError(step("(unquote)"));
		assertCompileError(step("(unquote 1 2)"));
	}

	@Test
	public void testSetHandling() {
		// sets used as functions act as a predicate
		assertCVMEquals(Boolean.TRUE,eval("(#{1 2} 1)"));

		// get returns value or nil
		assertEquals(1L,evalL("(get #{1 2} 1)"));
		assertSame(CVMBool.FALSE,eval("(get #{1 2} 3)"));
	}

	@Test
	public void testQuote() {
		assertEquals(Symbols.FOO,eval("(quote foo)"));
		assertEquals(Symbols.COUNT,eval("'count"));
		assertNull(eval("'nil"));
		assertEquals(Lists.of(Symbols.QUOTE,Symbols.COUNT),eval("''count"));
		assertEquals(Lists.of(Symbols.QUOTE,Lists.of(Symbols.UNQUOTE,Symbols.COUNT)),eval("''~count"));

		assertEquals(Keywords.STATE,eval("':state"));
		assertEquals(Lists.of(Symbols.INC,3L),eval("'(inc 3)"));

		assertEquals(Vectors.of(Symbols.INC,Symbols.DEC),eval("'[inc dec]"));

		assertSame(CVMBool.TRUE,eval("(= (quote a/b) 'a/b)"));

		assertEquals(Symbol.create("undefined-1"),eval("'undefined-1"));
	}

	@Test
	public void testQuoteDataStructures() {
		assertEquals(Maps.of(1,2,3,4), eval("`{~(inc 0) 2 3 ~(dec 5)}"));
		assertEquals(Sets.of(1,2,3),eval("`#{1 2 ~(dec 4)}"));

		// TODO: unquote-splicing in data structures.
	}

	@Test
	public void testQuoteCases() {
		// Tests from Racket / Scheme
		Context<?> ctx=step("(def x 1)");
		assertEquals(read("(a b c)"),eval(ctx,"`(a b c)"));
		assertEquals(read("(a b 1)"),eval(ctx,"`(a b ~x)"));
		assertEquals(read("(a b 3)"),eval(ctx,"`(a b ~(+ x 2))"));
		assertEquals(read("(a `(b ~x))"),eval(ctx,"`(a `(b ~x))"));
		assertEquals(read("(a `(b ~1))"),eval(ctx,"`(a `(b ~~x))"));
		assertEquals(read("(a `(b ~1))"),eval(ctx,"`(a `(b ~~`~x))"));
		assertEquals(read("(a `(b ~x))"),eval(ctx,"`(a `(b ~~'x))"));

		// Unquote does nothing inside a regular quote
		assertEquals(read("(a b (unquote x))"),eval(ctx,"'(a b ~x)"));
		assertEquals(read("(unquote x)"),eval(ctx,"'~x"));

		// Unquote escapes surrounding quasiquote
		assertEquals(read("(a b (quote 1))"),eval(ctx,"`(a b '~x)"));
	}


	@Test
	public void testNestedQuote() {
		assertEquals(RT.cvm(10L),eval("(+ (eval `(+ 1 ~2 ~(eval 3) ~(eval `(+ 0 4)))))"));

		assertEquals(RT.cvm(10L),eval("(let [a 2 b 3] (eval `(+ 1 ~a ~(+ b 4))))"));
	}

	@Test
	public void testQuotedMacro() {

		assertEquals(2L,evalL("(eval '(if true ~(if true 2 3)))"));
	}

	@Test
	public void testQuotedMetadata() {
		// From issue #267
		assertEquals(eval("'(defn foo ^{:a :b} [])"),eval("`(defn foo ^{:a :b} [])"));
		assertEquals(eval("'(defn foo ^{:a :b} [a])"),eval("`(defn foo ^{:a :b} [a])"));
	}


	@Test
	public void testLet() {
		assertEquals(Vectors.of(1L,3L),eval("(let [[a b] [3 1]] [b a])"));
		assertEquals(Vectors.of(2L,3L),eval("(let [[a & more] [1 2 3]] more)"));

		// results of bindings should be available for subsequent bindings
		assertEquals(Vectors.of(1L,2L,3L),eval("(let [a [1 2] b (conj a 3)] b)"));

		// Result of binding _ is ignored, though side effects must still happen
		assertUndeclaredError(step("(let [_ 1] _)"));
		assertEquals(Vectors.of(1L,2L),eval("(let [_ (def v [1 2])] v)"));

		// shouldn't be legal to let-bind qualified symbols
		assertCompileError(step("(let [foo/bar 1] _)"));

		// ampersand edge cases
		assertEquals(Vectors.of(1L,Vectors.of(2L),3L),eval("(let [[a & b c] [1 2 3]] [a b c])"));

		// bad uses of ampersand
		assertCompileError(step("(let [[a &] [1 2 3]] a)")); // ampersand at end
		assertCompileError(step("(let [[a & b & c] [1 2 3]] [a b c])")); // too many Cooks!

	}

	@Test
	public void testLetRebinding() {
		assertEquals(6L,evalL("(let [a 1 a (inc a) a (* a 3)] a)"));

		assertUndeclaredError(step("(do (let [a 1] a) a)"));
	}

	@Test
	public void testLoopBinding() {
		assertEquals(Vectors.of(1L,3L),eval("(loop [[a b] [3 1]] [b a])"));
		assertEquals(Vectors.of(2L,3L),eval("(loop [[a & more] [1 2 3]] more)"));
	}

	@Test
	public void testLoopRecur() {
		// infinite loop should run out of juice
		assertJuiceError(step("(loop [] (recur))"));

		// infinite loop with wrong arity should fail with arity error first
		assertArityError(step("(loop [] (recur 1))"));

		assertEquals(Vectors.of(3L,2L,1L),eval ("(loop [v [] n 3] (cond (> n 0) (recur (conj v n) (dec n)) v))"));

	}

	@Test
	public void testLookupAddress() {
		Lookup<?> l=comp("foo");
		assertEquals(Constant.of(HERO),l.getAddress()); // should match compilation address
	}

	@Test
	public void testFnArity() {
		assertNull(eval("((fn []))"));
		assertEquals(Vectors.of(2L,3L),eval("((fn [a] a) [2 3])"));
		assertArityError(step("((fn))"));
	}

	@Test
	public void testFnBinding() {
		assertEquals(Vectors.of(1L,3L),eval("(let [f (fn [[a b]] [b a])] (f [3 1]))"));
		assertEquals(Vectors.of(2L,3L),eval("(let [f (fn [[a & more]] more)] (f [1 2 3]))"));
		assertEquals(Vectors.of(2L,3L),eval("(let [f (fn [[_ & more]] more)] (f [1 2 3]))"));

		// Test that parameter binding of outer fn is accessible in inner fn closure.
		assertEquals(10L,evalL("(let [f (fn [g] (fn [x] (g x)))] ((f inc) 9))"));

		// this should fail because g is not in lexical bindings of f when defined
		assertUndeclaredError(step("(let [f (fn [x] (g x)) g (fn [y] (inc y))] (f 3))"));
	}

	@Test
	public void testMultiFn() {
		assertEquals(CVMLong.ZERO,eval("((fn ([] 0) ([x] 1)) )"));
		assertEquals(CVMLong.ONE,eval("((fn ([] 0) ([x] 1)) :foo)"));

		// Test closing over lexical environment in MultiFn
		assertEquals(43L,evalL("(let [a 42 f (fn ([b] (+ a b)) ([] 666)) ] (f 1))"));
	}

	@Test
	public void testBindings() {
		assertEquals (2L,evalL("(let [[] nil] 2)")); // nil acts like empty sequence here?
		assertEquals (2L,evalL("(let [[] []] 2)")); // empty binding vector is OK

		// empty binding vector (vararg length zero)
		assertEquals (Vectors.empty(),eval("(let [[& more] []] more)"));
		assertEquals (Vectors.empty(),eval("(let [[& more] nil] more)")); // nil acts like empty sequence?

		assertEquals (2L,evalL("(let [[a] #{2}] a)"));
		assertEquals (Sets.of(1L, 2L),eval("(into #{} (let [[a b] #{1 2}] [a b]))"));

		// TODO: should we allow this? Technically just one vararg...
		assertEquals (Vectors.of(1,2,3),eval("(let [[& &] [1 2 3]] &)"));

	}

	@Test
	public void testBindingError() {



		// this should fail because of insufficient arguments
		assertArityError(step("(let [[a b] [1]] a)"));

		// this should fail because of insufficient arguments
		assertArityError(step("(let [[a b] #{2}] a)"));

		// these should fail because of incorrect argument type
		assertArityError(step("(let [[a b] nil] a)")); // treated as empty sequence
		assertCastError(step("(let [[a b] :foo] a)"));

		// this should fail because of too many arguments
		assertArityError(step("(let [[a b] [1 2 3]] a)"));

		// this should fail because of bad ampersand usage
		assertCompileError(step("((fn [a &]) 1 2)"));

		// this should fail because of multiple ampersand usage
		assertCompileError(step("(let [[a & b & c] [1 2 3 4]] b)"));

		// insufficient arguments for variadic binding
		assertArityError(step("(let [[a & b c d] [1 2]])"));
	}

	@Test
	public void testBindingParamPriority() {
		// if closure is constructed correctly, fn param overrides previous lexical binding
		assertEquals(2L,evalL("(let [a 3 f (fn [a] a)] (f 2))"));

		// likewise, lexical parameter should override definition in environment
		assertEquals(2L,evalL("(do (def a 3) ((fn [a] a) 2))"));
	}

	@Test
	public void testLetVsDef() {
		assertEquals(Vectors.of(3L,12L,11L),eval("(do (def a 2) [(let [a 3] a) (let [a (+ a 10)] (def a (dec a)) a) a])"));
	}

	@Test
	public void testDiabolicals()  {
		// 2^10000 map, too deep to expand
		assertDepthError(context().expand(Samples.DIABOLICAL_MAP_2_10000));
		// 30^30 map, too much data to expand
		assertJuiceError(context().expand(Samples.DIABOLICAL_MAP_30_30));
	}

	@Test
	public void testDefExpander()  {
		Context<?> c=context();
		String source="(defexpander bex [x e] (syntax \"foo\"))";
		ACell exp=expand(source);
		assertTrue(exp instanceof AList);

		AOp<?> compiled=comp(exp,c);

		c=c.execute(compiled);
		assertNotError(c);
		assertTrue(c.getEnvironment().get(Symbol.create("bex")) instanceof AFn);
		assertTrue(c.getMetadata().get(Symbol.create("bex")).containsKey(Keywords.EXPANDER_Q));

		compiled=comp("(bex 2)",c);
		c=c.execute(compiled);
		assertEquals(Strings.create("foo"),c.getResult());
	}

	@Test public void testExpansion() {
		assertEquals(Keywords.FOO,expand(":foo"));

		assertEquals(Syntax.create(Keywords.FOO,Maps.of(Keywords.BAR,CVMBool.TRUE)),Reader.read("^:bar :foo"));
		assertEquals(Syntax.create(Keywords.FOO,Maps.of(Keywords.BAR,CVMBool.TRUE)),expand("^:bar :foo"));
	}

	@Test
	public void testExpandQuote()  {
		assertEquals(null,expand("nil"));
		assertEquals(Lists.of(Symbols.QUOTE,Symbols.FOO),expand("'foo"));
		assertEquals(Lists.of(Symbols.QUOTE,Lists.of(Symbols.UNQUOTE,Symbols.FOO)),expand("'~foo"));
		assertEquals(Lists.of(Symbols.QUOTE,Lists.of(Symbols.QUOTE,Lists.of(Symbols.UNQUOTE,Symbols.FOO))),expand("''~foo"));

		assertEquals(Lists.of(Symbols.QUASIQUOTE,Symbols.FOO),expand("`foo"));

	}

	@Test
	public void testQuoteCompile()  {
		assertEquals(Constant.create((ACell)null),comp("nil"));
		assertEquals(Lookup.create(HERO,Symbols.FOO),comp("foo"));
		assertEquals(Lookup.create(HERO,Symbols.FOO),comp("`~foo"));
	}

	@Test
	public void testMacrosInMaps() {
		assertEquals(Maps.of(1L,2L),eval("(eval '{(if true 1 2) (if false 1 2)})"));
		assertEquals(Maps.of(1L,2L),eval("(eval `{(if true 1 2) ~(if false 1 2)})"));
	}

	@Test
	public void testMacrosNested() {
		AVector<CVMLong> expected=Vectors.of(1L,2L);
		assertEquals(expected,eval("(when (or nil true) (and [1 2]))"));
	}
	
	@Test
	public void testMacrosInActor() {
		Context<?> ctx=context();
		ctx=step(ctx,"(def lib (deploy `(do (defmacro foo [x] :foo))))");
		Address addr=(Address) ctx.getResult();
		assertNotNull(addr);
		
		ctx=step(ctx,"(def baz (lib/foo 1))");
		assertEquals(Keywords.FOO,ctx.getResult());
		
		ctx=step(ctx,"(def bar ("+addr+"/foo 2))");
		assertEquals(Keywords.FOO,ctx.getResult());
	}

	@Test
	public void testMacrosInSets() {
		assertEquals(Sets.of(1L,2L),eval("(eval '#{(if true 1 2) (if false 1 2)})"));
		assertEquals(Sets.of(1L,2L),eval("(eval `#{(if true 1 2) ~(if false 1 2)})"));
	}
	
	@Test
	public void testStaticCompilation() {
		if (Constants.OPT_STATIC) {
			assertSame(CVMBool.TRUE,eval("(:static (lookup-meta 'count))"));
	
			// Static core function should compile to constant
			assertEquals(Constant.of(Core.COUNT),eval("(compile 'count)"));
		}

		// Aliases compile to dynamic lookup
		assertEquals(Lookup.create(Address.create(1), Symbols.COUNT),eval("(compile '#1/count)"));
		assertEquals(Lookup.create(Address.create(8), Symbols.TRANSFER),eval("(compile '#8/transfer)"));
		assertEquals(Lookup.create(Address.create(8888), Symbols.TRANSFER),eval("(compile '#8888/transfer)"));
}

	@Test
	public void testEdgeCases() {
		assertFalse(evalB("(= *juice* *juice*)"));
		assertEquals(Maps.of(1L,2L),eval("{1 2 1 2}"));

		// TODO: sanity check? Does/should this depend on map ordering?
		assertEquals(1L,evalL("(count {~(inc 1) 3 ~(dec 3) 4})"));

		assertEquals(Maps.of(11L,5L),eval("{~((fn [x] (do (return (+ x 7)) 100)) 4) 5}"));
		assertEquals(Maps.of(1L,2L),eval("{(inc 0) 2}"));

		// TODO: figure out correct behaviour for this. Depends on read vs. readSyntax?
		//assertEquals(4L,evalL("(count #{*juice* *juice* *juice* *juice*})"));
		//assertEquals(2L,evalL("(count {*juice* *juice* *juice* *juice*})"));
	}

	@Test
	public void testInitialEnvironment() {
		// list should be a core function
		ACell eval=eval("list");
		assertTrue(eval instanceof AFn);

		// if should be a macro implemented as an expander
		//  assertTrue(eval("if") instanceof AExpander);

		// def should be a special form, and evaluate to a symbol
		assertEquals(Symbols.DEF,eval("def"));
	}
}
