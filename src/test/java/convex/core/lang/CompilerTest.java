package convex.core.lang;

import static convex.test.Assertions.assertArityError;
import static convex.test.Assertions.assertCastError;
import static convex.test.Assertions.assertCompileError;
import static convex.test.Assertions.assertDepthError;
import static convex.test.Assertions.assertJuiceError;
import static convex.test.Assertions.assertUndeclaredError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.Init;
import convex.core.State;
import convex.core.data.AVector;
import convex.core.data.Keywords;
import convex.core.data.Lists;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.data.Vectors;
import convex.core.exceptions.ParseException;
import convex.core.lang.expanders.AExpander;
import convex.core.lang.ops.Constant;
import convex.core.lang.ops.Def;
import convex.core.lang.ops.Do;
import convex.core.lang.ops.Invoke;
import convex.core.lang.ops.Lambda;
import convex.core.lang.ops.Lookup;
import convex.core.util.Utils;
import convex.test.Samples;

public class CompilerTest {
	
	@SuppressWarnings("unchecked")
	public <T extends AOp<?>> T comp(String source, Context<?> context) {
		Object form=Reader.read(source);
		AOp<?> code = context.expandCompile(form).getResult();
		return (T) code;
	}
	
	public <T extends AOp<?>> T comp(String source) {
		return comp(source,CONTEXT);
	}
	
	private static final State INITIAL=TestState.INITIAL;
	private static final Context<?> CONTEXT=TestState.INITIAL_CONTEXT;

	@SuppressWarnings("unchecked")
	public static <T> Context<T> step(Context<?> c, String source) {
		Object form = Reader.readSyntax(source);
		c=c.expandCompile(form);
		if (c.isExceptional()) return (Context<T>) c;
		
		AOp<?> op = (AOp<?>) c.getResult();
		Context<T> rctx = (Context<T>) c.execute(op);
		return rctx;
	}

	public static <T> Context<T> step(String source) {
		return step(CONTEXT, source);
	}

	@SuppressWarnings("unchecked")
	public static <T> T eval(String source) {
		return (T) step(source).getValue();
	}
	
	public Syntax expand(String source) {
		try {
			Object form=Reader.read(source);
			Syntax expanded =CONTEXT.expand(form).getResult();
			return expanded;
		}
		catch (Exception e) {
			throw Utils.sneakyThrow(e);
		}
	}
	
	@Test 
	public void testConstants() {
		assertEquals(1L,(long)eval("1"));
		assertEquals(Samples.FOO,eval(":foo"));
		assertEquals('d',(char)eval("\\d"));
		assertEquals("baz",eval("\"baz\""));
		
		assertSame(Vectors.empty(),eval("[]"));
		assertSame(Lists.empty(),eval("()"));

		assertNull(eval("nil"));
		assertSame(Boolean.TRUE,eval("true"));
		assertSame(Boolean.FALSE,eval("false"));
	}
	
	@Test public void testDo() {
		assertEquals(1L,(long)eval("(do 2 1)"));
		assertEquals(2L,(long)eval("(do *depth*)"));
		assertEquals(3L,(long)eval("(do (do *depth*))"));
	}
	
	@Test public void testMinCompileRegression() throws IOException {
		String src=Utils.readResourceAsString("testsource/min.con");
		Object form=Reader.read(src);
		Context<Syntax> exp=CONTEXT.expand(form);
		assertFalse(exp.isExceptional());
		Context<AOp<Object>> com=CONTEXT.compile(exp.getResult());
		assertFalse(com.isExceptional());
	}
	
	@Test public void testFnCasting() {
		assertEquals(1L,(long)eval("({2 1} 2)"));
		assertNull(eval("({2 1} 1)"));
		assertEquals(3L,(long)eval("({2 1} 1 3)"));
		assertSame(Boolean.TRUE,eval("(#{2 1} 1)"));
		assertSame(Boolean.TRUE,eval("(#{nil 1} nil)"));
		assertSame(Boolean.FALSE,eval("(#{2 1} 7)"));
		assertSame(Boolean.FALSE,eval("(#{2 1} nil)"));
		assertEquals(7L,(long)eval("([] 3 7)"));
		
		assertEquals(3L,(long)eval("(:foo {:bar 1 :foo 3})"));
		assertNull(eval("(:foo {:bar 1})"));
		assertEquals(7L,(long)eval("(:baz {:bar 1 :foo 3} 7)"));
		assertEquals(2L,(long)eval("(:foo nil 2)")); // TODO: is this sane? treat nil as empty?
		
		// zero arity failing
		assertArityError(step("(:foo)"));
		assertArityError(step("({})"));
		assertArityError(step("(#{})"));
		assertArityError(step("([])"));

		// non-associative lookup
		assertCastError(step("(:foo 1 2)"));
		
		// too much arity
		assertArityError(step("({} 1 2 3)"));
		assertThrows(IndexOutOfBoundsException.class,()->eval("([] 1)"));
		assertArityError(step("([] 1 2 3)"));
		assertArityError(step("(:foo 1 2 3)")); // arity > type
	}
	
	@Test public void testApply() {
		assertEquals(true,eval("(apply = nil)"));
		assertEquals(true,eval("(apply = [1 1])"));
		assertEquals(false,eval("(apply = [1 1 nil])"));
		
		assertArityError(step("(apply)"));

	}
	
	@Test public void testLambda() {
		assertEquals(2L,(long)eval("((fn [a] 2) 3)"));
		assertEquals(3L,(long)eval("((fn [a] a) 3)"));
		assertEquals(2L,(long)eval("((fn [a] *depth*) 3)"));
	}

	
	@Test public void testDef() {
		assertEquals(2L,(long)eval("(do (def a 2) (def b 3) a)"));
		assertEquals(7L,(long)eval("(do (def a 2) (def a 7) a)"));
		
		// aliased symbols should get own entry
		assertEquals(6L,(step("(do (def bar 6) (def foo/bar 3) bar)").getResult()));
		assertEquals(3L,(step("(do (def foo/bar 3) (def bar 6) foo/bar)").getResult()));
		
		
		// TODO: check if these are most logical error types?
		assertCompileError(step("(def :a 1)"));
		assertCompileError(step("(def a)"));
		assertCompileError(step("(def a 2 3)"));
	}
	
	@Test public void testDefMetadataOnLiteral() {
		Context<?> ctx=step("(def a ^:foo 2)");
		Syntax stx=ctx.getEnvironment().getEntry(Symbol.create("a")).getValue();
		assertEquals(Boolean.TRUE,stx.getMeta().get(Keywords.FOO));
	}
	
	@Test public void testDefMetadataOnForm() {
		Context<?> ctx=step("(def a ^:foo (+ 1 2))");
		Syntax stx=ctx.getEnvironment().getEntry(Symbol.create("a")).getValue();
		assertEquals(3L,(long)stx.getValue());
		assertEquals(Boolean.TRUE,stx.getMeta().get(Keywords.FOO));
	}
	
	@Test public void testDefMetadataOnSymbol() {
		Context<?> ctx=step("(def ^{:foo true} a (+ 1 2))");
		Syntax stx=ctx.getEnvironment().getEntry(Symbol.create("a")).getValue();
		assertEquals(3L,(long)stx.getValue());
		assertEquals(Boolean.TRUE,stx.getMeta().get(Keywords.FOO));
	}
	
	@Test public void testCond() {
		assertEquals(1L,(long)eval("(cond nil 2 1)"));
		assertEquals(4L,(long)eval("(cond nil 2 false 3 4)"));
		assertEquals(2L,(long)eval("(cond 1 2 3 4)"));
		assertNull(eval("(cond)"));
		assertNull(eval("(cond false true)"));
	}
	
	@Test public void testIf() {
		assertNull(eval("(if false 4)"));
		assertEquals(4L,(long)eval("(if true 4)"));
		assertEquals(2L,(long)eval("(if 1 2 3)"));
		assertEquals(3L,(long)eval("(if nil 2 3)"));
		assertEquals(7L,(long)eval("(if :foo 7)"));
		assertEquals(2L,(long)eval("(if true *depth*)"));
		
		// test that if macro expansion happens correctly inside vector
		assertEquals(Vectors.of(3L,2L),eval("[(if nil 2 3) (if 1 2 3)]"));

		// test that if macro expansion happens correctly inside other macro
		assertEquals(3L,(long)eval("(if (if 1 nil 3) 2 3)"));
		
		// ARITY error if too few or too many branches
		assertArityError(step("(if :foo)"));
		assertArityError(step("(if :foo 1 2 3 4 5)"));
	}
	
	@Test
	public void testStackOverflow()  {
		// fake state with default juice
		Context<?> c=Context.createFake(INITIAL, TestState.HERO);
		
		AOp<Long> op=Do.create(
				    // define a nasty function that calls its argument recursively on itself
					Def.create("fubar", 
							Lambda.create(Vectors.of(Syntax.create(Symbol.create("func"))), 
											Invoke.create(Lookup.create("func"),Lookup.create("func")))),
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
		assertEquals(3L,(long)eval("~(+ 1 2)"));
		
		assertEquals(2L,(long)eval("~*depth*")); // depth in compiler
		assertEquals(3L,(long)eval("~(do *depth*)")); // depth in compiler
		assertEquals(1L,(long)eval("'~*depth*")); // depth in expansion
		assertEquals(2L,(long)eval("'~(do *depth*)")); // depth in expansion

		// not we require compilation down to a single constant
		assertEquals(Constant.create(7L),comp("~(+ 7)"));
		
		assertArityError(step("~(inc)"));
		assertCastError(step("~(inc :foo)"));
		
		// TODO: what are right error types here?
		assertCompileError(step("(unquote)"));
		assertCompileError(step("(unquote 1 2)"));
	}
	
	@Test 
	public void testSetHandling() {
		// sets used as functions act as a predicate
		assertEquals(Boolean.TRUE,eval("(#{1 2} 1)"));
		
		// get returns value or nil
		assertEquals(1L,(long)eval("(get #{1 2} 1)"));
		assertNull(eval("(get #{1 2} 3)"));
	}
	
	@Test 
	public void testQuote() {
		assertNull(eval("'~nil"));
		assertNull(eval("'nil"));
		assertEquals(Symbols.COUNT,eval("'count"));
		assertEquals(Lists.of(Symbols.QUOTE,Symbols.COUNT),eval("''count"));
		assertEquals(Keywords.STATE,eval("':state"));
		assertEquals(Keywords.STATE,eval("(let [a :state] '~a)"));
		assertEquals(Vectors.of(Symbols.INC,Symbols.DEC),eval("'[inc dec]"));
		assertEquals(Vectors.of(1L,3L),eval("'[1 ~(+ 1 2)]"));
		assertEquals(Lists.of(Symbols.INC,3L),eval("'(inc 3)"));
		assertEquals(Lists.of(Symbols.INC,3L),eval("'(inc ~(+ 1 2))"));
		
		assertTrue((boolean)eval("(= (quote a/b) 'a/b)"));
		
		assertEquals(Symbol.create("undefined-1"),eval("'undefined-1"));
		assertUndeclaredError(step("'~undefined-1"));
		assertUndeclaredError(step("~'undefined-1"));
	}
	
	@Test 
	public void testNestedQuote() {
		assertEquals(10L,(long)eval("(+ (eval '(+ 1 ~2 ~(eval 3) ~(eval '(+ 0 4)))))"));

		assertEquals(10L,(long)eval("(let [a 2 b 3] (eval '(+ 1 ~a ~(+ b 4))))"));
	}
	
	@Test 
	public void testQuotedMacro() {

		assertEquals(2L,(long)eval("(eval '(if true ~(if true 2 3)))"));
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
		assertEquals(6L,(long)eval("(let [a 1 a (inc a) a (* a 3)] a)"));
		
		assertUndeclaredError(step("(do (let [a 1] a) a)"));
	}
	
	@Test
	public void testLoopBinding() {
		assertEquals(Vectors.of(1L,3L),eval("(loop [[a b] [3 1]] [b a])"));
		assertEquals(Vectors.of(2L,3L),eval("(loop [[a & more] [1 2 3]] more)"));
	}
	
	@Test
	public void testLoopRecur() {
		assertEquals(Vectors.of(3L,2L,1L),eval ("(loop [v [] n 3] (if (> n 0) (recur (conj v n) (dec n)) v))"));
		
		// infinite loop should run out of juice
		assertJuiceError(step("(loop [] (recur))"));
		
		// infinite loop with wrong arity should fail with arity error first
		assertArityError(step("(loop [] (recur 1))"));		
	}
	
	@Test
	public void testLookupAddress() {
		Lookup<?> l=comp("foo");
		assertEquals(Init.HERO,l.getAddress());
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
		assertEquals(10L,(long)eval("(let [f (fn [g] (fn [x] (g x)))] ((f inc) 9))"));
		
		// this should fail because g is not in lexical bindings of f when defined
		assertUndeclaredError(step("(let [f (fn [x] (g x)) g (fn [y] (inc y))] (f 3))"));
	}
	
	
	@Test
	public void testBindingError() {
		// this should fail because of bad ampersand usage
		assertCompileError(step("((fn [a &]) 1 2)"));
	}
	
	@Test
	public void testBindingParamPriority() {
		// if closure is constructed correctly, fn param overrides previous lexical binding
		assertEquals(2L,(long)eval("(let [a 3 f (fn [a] a)] (f 2))"));

		// likewise, lexical parameter should override definition in environment
		assertEquals(2L,(long)eval("(do (def a 3) ((fn [a] a) 2))"));
	}
	
	@Test
	public void testLetVsDef() {
		assertEquals(Vectors.of(3L,12L,11L),eval("(do (def a 2) [(let [a 3] a) (let [a (+ a 10)] (def a (dec a)) a) a])"));
	}
	
	@Test
	public void testDiabolicals()  {
		// 2^10000 map, too deep to expand
		assertDepthError(CONTEXT.expand(Samples.DIABOLICAL_MAP_2_10000));
		// 30^30 map, too much data to expand
		assertJuiceError(CONTEXT.expand(Samples.DIABOLICAL_MAP_30_30));
	}
	
	@Test
	public void testExpander()  {
		AExpander ex=eval("(expander (fn [x e] x))");
		assertEquals("foo",ex.expand("foo", ex, CONTEXT).getResult().getValue());
		
		// Fails because function call compiled before macro is defined.... TODO verify if OK?
		assertCastError(step("(do (def bex (expander (fn [x e] \"foo\"))) (bex 2))"));
	
		Context<?> c=CONTEXT;
		c=c.execute(comp("(defexpander bex [x e] \"foo\")"));
		c=c.execute(comp("(bex 2)",c));
		assertEquals("foo",c.getResult());
	}
	
	@Test 
	public void testMacrosInMaps() {
		assertEquals(Maps.of(1L,2L),eval("(eval '{(if true 1 2) (if false 1 2)})"));		
		assertEquals(Maps.of(1L,2L),eval("(eval '{(if true 1 2) ~(if false 1 2)})"));		
	}
	
	@Test 
	public void testMacrosNested() {
		AVector<Long> expected=Vectors.of(1L,2L);
		assertEquals(expected,eval("(when (or nil true) (and [1 2]))"));		
	}
	
	@Test 
	public void testMacrosInSets() {
		assertEquals(Sets.of(1L,2L),eval("(eval '#{(if true 1 2) (if false 1 2)})"));		
		assertEquals(Sets.of(1L,2L),eval("(eval '#{(if true 1 2) ~(if false 1 2)})"));		
	}
	
	@Test
	public void testMacro()  {
		Context<?> c=CONTEXT;
		AOp<?> defop=comp("(def c1 (macro [z] (str z)))");
		c=c.execute(defop);
		c=c.execute(comp("(c1 bar)",c));
		assertEquals("bar",c.getResult());
		
		// TODO: think about this.
		// assertEquals(2L,(long)eval("((macro [x] 2) (return 3))"));
	}
	
	@Test 
	public void testEdgeCases() {
		assertFalse((Boolean)eval("(= *juice* *juice*)"));
		assertEquals(Maps.of(1L,2L),eval("{1 2 1 2}"));
		
		// TODO: sanity check? Does/should this depend on map ordering?
		assertEquals(1L,(long)eval("(count {~(inc 1) 3 ~(dec 3) 4})"));
		
		assertEquals(Maps.of(11L,5L),eval("{~((fn [x] (do (return (+ x 7)) 100)) 4) 5}"));
		assertEquals(Maps.of(1L,2L),eval("{(inc 0) 2}"));
		
		assertEquals(2L,(long)eval("(count {*juice* *juice* *juice* *juice*})"));
		assertEquals(4L,(long)eval("(count #{*juice* *juice* *juice* *juice*})"));
	}
	
	@Test 
	public void testInitialEnvironment() {
		// list should be a core function
		assertTrue(eval("list") instanceof AFn);
		
		// if should be a macro implemented as an expander
		assertTrue(eval("if") instanceof AExpander);
		
		// def should be a special form, and evaluate to a symbol
		assertEquals(Symbols.DEF,eval("def"));
	}
}
