package convex.core.lang;

import static convex.test.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import convex.core.data.Blob;
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
import convex.core.init.BaseTest;
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
		super(BaseTest.STATE);
	}

	@Test
	public void testConstants() {
		// Basic constant values should evaluate to themselves
		
		assertEquals(1L,evalL("1"));
		assertEquals(Samples.FOO,eval(":foo"));
		assertCVMEquals('d',eval("\\d"));
		assertCVMEquals("baz",eval("\"baz\""));

		assertSame(Vectors.empty(),eval("[]"));
		assertSame(Lists.empty(),eval("()"));
		assertSame(Sets.empty(),eval("#{}"));
		assertSame(Maps.empty(),eval("{}"));

		assertNull(eval("nil"));
		assertSame(CVMBool.TRUE,eval("true"));
		assertSame(CVMBool.FALSE,eval("false"));
		
		// basic constant values should compile to themselves!
		
		assertEquals(Constant.of(1L),comp("1"));
		assertEquals(Constant.of(Samples.FOO),comp(":foo"));
		assertEquals(Constant.of('d'),comp("\\d"));
		assertEquals(Constant.of("baz"),comp("\"baz\""));
		
		assertEquals(Constant.of(Vectors.empty()),comp("[]"));
		assertEquals(Constant.of(Lists.empty()),comp("()"));
		assertEquals(Constant.of(Sets.empty()),comp("#{}"));
		assertEquals(Constant.of(Maps.empty()),comp("{}"));
		
		assertEquals(Constant.of(Blob.EMPTY),comp("0x"));
	}
	
	@Test
	public void testConstantCompilation() {

		// special cases for optimised constants
		assertSame(Constant.NULL, comp("nil"));
		assertSame(Constant.TRUE,  comp( "true"));
		assertSame(Constant.FALSE,  comp("false"));
		assertSame(Constant.EMPTY_VECTOR, comp("[]"));
		assertSame(Constant.EMPTY_LIST, comp("()"));
		assertSame(Constant.EMPTY_MAP, comp("{}"));
		assertSame(Constant.EMPTY_SET, comp("#{}"));
		assertSame(Constant.EMPTY_STRING,comp("\"\""));

	}
	
	@Test public void testComments() {
		// comments / whitespace should be ignore
		assertEquals(Vectors.of(1,2,3),eval("[1 2 3] ;; Random stuff"));
		assertEquals(Vectors.of(1,3),eval("[1 #_2 3]"));
		assertEquals(Vectors.of(1,3),eval("[1 #_(+ 3 4) #_ 8 3]"));
		assertEquals(Vectors.of(1,3),eval("[1 #_(+ 3 #_4) 3]"));
		
		// Comments only -> no form to execute!
		assertThrows(ParseException.class,()->step("; No code here to run!"));
		assertThrows(ParseException.class,()->step("#_(+ 3 4)"));
		assertThrows(ParseException.class,()->step("#_(+ 3 4 ()"));
	}

	@Test public void testDo() {
		assertNull(eval("(do)"));
		assertEquals(1L,evalL("(do 2 1)"));
		assertEquals(1L,evalL("(do 2 *depth*)")); // Adds one level to initial depth
		assertEquals(0L,evalL("(do (do *depth*))")); // single entry 'do's get compiled out
	}

	@Test public void testMinCompileRegression() throws IOException {
		// a simple function to test
		Context c=context();
		String src=Utils.readResourceAsString("/testsource/min.con");
		ACell form=Reader.read(src);
		Context exp=c.expand(form);
		assertNotError(exp);
		Context com=c.compile(exp.getResult());
		assertNotError(com);
		
		c=c.eval(com.getResult());
		assertNotError(c);
		
		// should get minimum value from arguments
		assertCVMEquals(4,eval(c,"(mymin 6 4 9)"));
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
	
	@Test public void testBadCode() {
		assertCastError(step("(nil 1 2)"));
		assertCastError(step("(3 1 2)"));
	}

	@Test public void testLambda() {
		assertEquals(2L,evalL("((fn [a] 2) 3)"));
		assertEquals(3L,evalL("((fn [a] a) 3)"));

		assertEquals(1L,evalL("((fn [a] *depth*) 3)")); // Level of invoke depth
	}


	@Test public void testDef() {
		assertCVMEquals(2L,eval("(do (def a 2) (def b 3) a)"));
		assertCVMEquals(7L,eval("(do (def a 2) (def a 7) a)"));
		assertCVMEquals(9L,eval("(do (def a 9) (def a) a)"));
		assertCVMEquals(9L,eval("(do (def a) (def a 9) a)"));
		assertNull(eval("(do (def a 9) (def a nil) a)"));

		// TODO: check if these are most logical error types?
		assertCompileError(step("(def :a 1)"));
		assertCompileError(step("(def a 2 3)"));
	}
	
	@Test public void testDefOverCore() {
		// TODO: This might not be sane?
		if (Constants.OPT_STATIC) {
			assertCVMEquals(Core.COUNT,eval("(do (def count 13) count)"));
		} else {
			assertCVMEquals(13L,eval("(do (def count 13) count)"));
		}
	}

	@Test public void testDefMetadataOnLiteral() {
		Context ctx=step("(def a ^:foo 2)");
		assertNotError(ctx);
		AHashMap<ACell,ACell> m=ctx.getMetadata().get(Symbol.create("a"));
		assertSame(CVMBool.TRUE,m.get(Keywords.FOO));
	}

	@Test public void testDefMetadataOnForm() {
		String code="(def a ^:foo (+ 1 2))";
		Symbol sym=Symbol.create("a");

		Context ctx=step(code);
		assertNotError(ctx);
		ACell v=ctx.getEnvironment().get(sym);
		assertCVMEquals(3L,v);
		assertSame(CVMBool.TRUE,ctx.getMetadata().get(sym).get(Keywords.FOO));
	}

	@Test public void testDefMetadataOnSymbol() {
		Context ctx=step("(def ^{:foo true} a (+ 1 2))");
		assertNotError(ctx);

		Symbol sym=Symbol.create("a");
		ACell v=ctx.getEnvironment().get(sym);
		assertCVMEquals(3L,v);
		assertSame(CVMBool.TRUE,ctx.getMetadata().get(sym).get(Keywords.FOO));
	}

	@Test public void testCond() {
		// nil behaves as false
		assertEquals(1L,evalL("(cond nil 2 1)"));
		
		// truthy values
		assertEquals(2L,evalL("(cond true 2 1)"));
		assertEquals(2L,evalL("(cond :false 2 1)"));
		
		// multiple tests
		assertEquals(4L,evalL("(cond nil 2 false 3 4)"));
		assertEquals(2L,evalL("(cond 1 2 3 4)"));
		
		// Fallback value only
		assertEquals(9L,evalL("(cond 9)"));
		
		// Nil result with no fallback value
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
	public void testCoreImplicits() {
		assertEquals(Core.COUNT,eval("#%count"));
		assertCVMEquals(1L,eval("(#%count [2])"));
	}

	@Test
	public void testStackOverflow()  {
		// fake state with default juice
		Context c=context();

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

		assertArityError(step("(unquote)"));
		assertArityError(step("(unquote 1 2)"));
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
		
		// eval strips one level of quote, leaves internal stuff unchanged
		assertEquals(Lists.of(Symbols.QUOTE,Symbols.COUNT),eval("''count"));
		assertEquals(Lists.of(Symbols.QUOTE,Lists.of(Symbols.UNQUOTE,Symbols.COUNT)),eval("''~count"));

		assertEquals(Keywords.STATE,eval("':state"));
		assertEquals(Lists.of(Symbols.INC,3L),eval("'(inc 3)"));

		assertEquals(Vectors.of(Symbols.INC,Symbols.DEC),eval("'[inc dec]"));

		assertSame(CVMBool.TRUE,eval("(= (quote a/b) 'a/b)"));

		assertEquals(Symbol.create("undefined-1"),eval("'undefined-1"));
		
		assertEquals(read("(quote (1 2))"),expand("'(1 2)"));
		assertEquals(read("(quote (if))"),expand("'(if)"));

		// unquote doesn't do anything in regular quote
		assertEquals(eval("(quote (unquote 17))"),eval("'~17"));
		
		// Macros don't expand within in regular quote
		assertEquals(read("(if)"),eval("(quote (if))"));
		
		assertArityError(step("(quote a b)"));

	}
	
	@Test 
	public void testQuotedJuice() {
		assertJuiceError(step("`(do ~(loop [] (recur)))"));
	}
	
	@Test 
	public void testDataLiterals() {
		assertEquals(comp("(hash-set 1 2)"),comp("#{1 2}"));
		assertEquals(Constant.of(Sets.empty()),comp("#{}"));
		
		// Note inlining of vector from core in vector literal
		assertEquals(comp("(~vector 1 2 3)"),comp("[1 2 3]"));
		assertEquals(Constant.of(Vectors.empty()),comp("[]"));
	}

	@Test
	public void testQuoteDataStructures() {
		assertEquals(Maps.of(1,2,3,4), eval("{~(inc 0) 2 3 ~(dec 5)}"));
		assertEquals(Sets.of(1,2,3),eval("#{1 2 ~(dec 4)}"));

		// TODO: unquote-splicing in data structures.
	}

	@Test
	public void testQuoteCases() {
		Context ctx=step("(def x 1)");
	
		// Unquote does nothing inside a regular quote
		assertEquals(read("(a b (unquote x))"),eval(ctx,"'(a b ~x)"));
		assertEquals(read("(unquote x)"),eval(ctx,"'~x"));

		// Unquote escapes surrounding quasiquote
		assertEquals(read("(a b (quote 1))"),eval(ctx,"`(a b '~x)"));
 

		// Tests from Racket / Scheme
		assertEquals(read("(a b c)"),eval(ctx,"`(a b c)"));
		assertEquals(read("(a b 1)"),eval(ctx,"`(a b ~x)"));
		assertEquals(read("(a b 3)"),eval(ctx,"`(a b ~(+ x 2))"));
		assertEquals(read("(a `(b ~x))"),eval(ctx,"`(a `(b ~x))"));
		assertEquals(read("(a `(b ~1))"),eval(ctx,"`(a `(b ~~x))"));
		assertEquals(read("(a `(b ~1))"),eval(ctx,"`(a `(b ~~`~x))"));
		assertEquals(read("(a `(b ~x))"),eval(ctx,"`(a `(b ~~'x))"));
	}


	@Test
	public void testNestedQuote() {
		assertCVMEquals(10,eval("(+ (eval `(+ 1 ~2 ~(eval 3) ~(eval `(+ 0 4)))))"));

		assertCVMEquals(10,eval("(let [a 2 b 3] (eval `(+ 1 ~a ~(+ b 4))))"));
	}

	@Test
	public void testQuotedMacro() {
		assertEquals(2L,evalL("(eval '(if true ~(if true 2 3)))"));
	}
	
	@Test
	public void testRawUnquote() {
		assertUndeclaredError(step("(do (def foo 12) ~foo)"));
	}
	
	@Test
	public void testQuasiquote() {
		assertEquals(Vectors.of(1,2,3),eval("(quasiquote [1 ~2 ~(dec 4)])"));
		assertEquals(eval("(quasiquote (quasiquote (unquote 1)))"),eval("``~1"));
		assertEquals(Constant.of(10),comp("`~`~10"));
		
		assertNull(eval("(quasiquote (unquote nil))"));

		assertEquals(Vectors.of(1,2,3,Lists.empty(),null),eval("(quasiquote [1 ~2 ~(dec 4) () nil])"));
		assertEquals(Symbols.FOO,eval("(quasiquote foo)"));

		assertEquals(Syntax.create(CVMBool.TRUE),eval("(eval `(do (syntax true nil)))"));
		assertEquals(CVMBool.TRUE,eval("(eval `(do ~(syntax true nil)))"));
		
		
		assertEquals(read("(quote 3)"),eval("(quasiquote (quote ~(inc 2)))"));

		assertEquals(Vectors.of(1,Vectors.of(2),3),eval("(let [a 2] (quasiquote [1 [~a] ~(let [a 3] a)]))"));
		assertEquals(Maps.of(2,3,Maps.empty(),5),eval("(eval (quasiquote {~(inc 1) 3 {} ~(dec 6)}))"));	
		
		// Compilation checks
		assertEquals(Constant.of(10),comp("(quasiquote (unquote (quasiquote (unquote 10))))"));
	}
	
	@Test
	public void testQuasiquoteExpansions() {
		// quasiquote of something with no unquotes should expand to an equivalent quote
		assertEquals(read("(quote foo)"),expand("(quasiquote foo)"));
		assertEquals(read("(quote false)"),expand("(quasiquote false)"));
		assertEquals(read("(quote nil)"),expand("(quasiquote nil)"));
		assertEquals(read("(quote 17)"),expand("(quasiquote 17)"));
		assertEquals(read("(quote [1 2 nil])"),expand("(quasiquote [1 2 nil])"));
		
		// quasiquote of an unquote should expand like whatever is in the unquote
		assertEquals(Constant.NULL,expand("(quasiquote ~nil)")); // TODO: it this what we want?
		assertEquals(CVMLong.ONE,expand("(quasiquote ~1)"));
		assertEquals(read("(cond 1 2 3)"),expand("(quasiquote ~(if 1 2 3))"));
		
		assertEquals(read("[(quote foo) (inc 2)]"),expand("(quasiquote [foo (unquote (inc 2))])"));
		
		
		assertEquals(read("[foo 3]"),eval("(expand-1 `[foo (unquote (inc 2))])"));
	}
	
	@Test 
	public void testQuasiquoteHelpers() {
		// returns null, because forms don't require generator (already self-generating)
		assertNull(eval("(quasiquote* [1 2] 1)"));
		assertNull(eval("(quasiquote* 7 1)")); 
		assertNull(eval("(quasiquote* nil 2)")); 
		assertNull(eval("(qq-seq [] 1)"));
		
		assertEquals(Vectors.of(2),eval("(qq-seq '[~2] 1)"));
		assertEquals(Vectors.of(1,2,3),eval("(eval (qq-seq '[1 ~2 ~(dec 4)] 1))"));

		assertEquals(read("(quote (quasiquote foo))"),eval("(qq* '(quasiquote foo) 1)"));

		
		assertEquals(read("(quote 7)"),eval("(qq* 7 1)"));
		assertEquals(read("(quote foo)"),eval("(qq* 'foo 1)"));
		assertEquals(read("(quote (quote foo))"),eval("(qq* ''foo 1)"));

		assertEquals(read("(inc 1)"),eval("(qq* '(unquote (inc 1)) 1)"));
	}
	
	@Test
	public void testUnquoteSplicing() {
		// TODO:
		// assertEquals(Vectors.of(1,2,3),eval("(let [a [2 3]] `[1 ~@a])"));
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
		
		// TODO: should we be able to let-bind symbols with metadata?
		// assertCVMEquals(7,eval("(let [^:foo a 7] a)"));

		// test set! on a let-bound variable
		assertCVMEquals(3,eval("(let [a 2] (set! a 3) a)"));

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
		
		// TODO: be aware that loops restore lexical env?
		// assertCVMEquals(5,eval("(let [a 2] (loop [] (set! a 5)) a)"));


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
	public void testMultiColonKeyword () {
		// see #436 for regression case
		assertNotEquals(eval("'foo"),eval("::foo"));
		assertNotEquals(eval(":foo"),eval("::foo"));
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
		Context c=context();
		String source="(defexpander bex [x e] (syntax \"foo\"))";
		ACell exp=expand(source);
		assertTrue(exp instanceof AList);

		AOp<?> compiled=comp(exp,c);

		c=c.execute(compiled);
		assertNotError(c);
		assertTrue(c.getEnvironment().get(Symbol.create("bex")) instanceof AFn);
		assertTrue(c.getMetadata().get(Symbol.create("bex")).containsKey(Keywords.EXPANDER_META));

		compiled=comp("(bex 2)",c);
		c=c.execute(compiled);
		assertEquals(Strings.create("foo"),c.getResult());
	}

	@Test public void testExpansion() {
		assertNull(expand("nil"));
		assertEquals(Keywords.FOO,expand(":foo"));
		assertSame(Maps.empty(),expand("{}"));
		assertEquals(Maps.of(1,2),expand("{1 2}"));

		assertEquals(Syntax.create(Keywords.FOO,Maps.of(Keywords.BAR,CVMBool.TRUE)),Reader.read("^:bar :foo"));
		assertEquals(Syntax.create(Keywords.FOO,Maps.of(Keywords.BAR,CVMBool.TRUE)),expand("^:bar :foo"));
		
		assertEquals(read("(cond 1 2 3)"),expand("(if 1 2 3)"));

	}
	
	@Test public void testExpandDataStructures() {
		assertEquals(Reader.read("{1 2}"),expand("{1 2}"));
		assertEquals(Reader.read("(1 2)"),expand("(1 2)"));
		
		// TODO: check this once quasiquote optimisation complete
		// assertEquals(Reader.read("(quote {1 2})"),expand("`{1 2}"));
	}

	@Test
	public void testExpandQuote()  {
		assertEquals(null,expand("nil"));
		assertEquals(Lists.of(Symbols.QUOTE,Symbols.FOO),expand("'foo"));
		assertEquals(Lists.of(Symbols.QUOTE,Lists.of(Symbols.UNQUOTE,Symbols.FOO)),expand("'~foo"));
		assertEquals(Lists.of(Symbols.QUOTE,Lists.of(Symbols.QUOTE,Lists.of(Symbols.UNQUOTE,Symbols.FOO))),expand("''~foo"));

		assertEquals(Lists.of(Symbols.QUASIQUOTE,Symbols.FOO),read("`foo"));
		
		// TODO: quasiquote eliminated by expansion, ensure this works after Convex Lisp quasiquoter complete
		//assertEquals(Lists.of(Symbols.QUOTE,Symbols.FOO),expand("`foo"));

	}

	@Test
	public void testQuoteCompile()  {
		assertEquals(Constant.create((ACell)null),comp("nil"));
		assertEquals(Lookup.create(HERO,Symbols.FOO),comp("foo"));
		assertEquals(Lookup.create(HERO,Symbols.FOO),comp("`~foo"));
	}

	@Test
	public void testMacrosInMaps() {
		// System.out.println(expand("`{(if true 1 2) ~(if false 1 2)}"));
		// System.out.println(eval("(list (quote hash-map) (quote (if true 1 2)) (if false 1 2))"));
		assertEquals(Maps.of(1L,2L),eval("(eval '{(if true 1 2) (if false 1 2)})"));
		assertEquals(Maps.of(1L,2L),eval("(eval `{(if true 1 2) ~(if false 1 2)})"));
	}
	
	@Test
	public void testMacrosInSets() {
		assertEquals(Sets.of(1L,2L),eval("(eval '#{(if true 1 2) (if false 1 2)})"));
		assertEquals(Sets.of(1L,2L),eval("(eval `#{(if true 1 2) ~(if false 1 2)})"));
	}

	@Test
	public void testMacrosNested() {
		AVector<CVMLong> expected=Vectors.of(1L,2L);
		assertEquals(expected,eval("(when (or nil true) (and [1 2]))"));
	}
	
	@Test public void testExternalCompile() {
		// Inspired by #377, thanks @Darkneew!
		Context ctx=context();
		ctx=step(ctx,"(def a (deploy `(defn ^:callable eval [code] (eval-as *address* code))))");
		assertTrue(ctx.getResult() instanceof Address);
		
		ctx=step(ctx,"(call a (eval '(defn foo [arg] arg)))");
		assertTrue(ctx.getResult() instanceof AFn);
		
		assertTrue(ctx.getLocalBindings().isEmpty());
		
		ctx=step(ctx,"(a/foo 1)");
		assertCVMEquals(1L,ctx.getResult());
	}
	
	@Test public void testEvalCompile() {
		// Inspired by #377, thanks @Darkneew!
		Context ctx=context();
		ctx=step(ctx,"(eval `(defn identity [arg] arg))");
		assertTrue(ctx.getResult() instanceof AFn);
		
		ctx=step(ctx,"(identity 1)");
		assertCVMEquals(1L,ctx.getResult());
	}
	
	@Test public void testNestedEvalRegression() {
		// Test for nasty case if eval captured caller's local bindings (messing up lexical argument positions etc.)
		Context ctx=context();
		ctx=step(ctx,"((fn [code] (eval code)) '(defn g [x] x))");
		ctx=step(ctx,"(g 13)");
		assertCVMEquals(13L,ctx.getResult());
	}
	
	@Test
	public void testMacrosInActor() {
		Context ctx=context();
		ctx=step(ctx,"(def lib (deploy `(do (defmacro foo [x] :foo))))");
		Address addr=(Address) ctx.getResult();
		assertNotNull(addr);
		
		ctx=step(ctx,"(def baz (lib/foo 1))");
		assertEquals(Keywords.FOO,ctx.getResult());
		
		ctx=step(ctx,"(def bar ("+addr+"/foo 2))");
		assertEquals(Keywords.FOO,ctx.getResult());
	}
	
	@Test public void testMacroDefinition() {
		Context ctx=context();
		ctx=exec(ctx,"(defmacro fot [a b c] a)");
		
		assertCVMEquals(2,eval(ctx,"(fot (+ 1 1) 3 4)"));
		assertCVMEquals(2,eval(ctx,"("+ctx.getAddress()+"/fot (+ 1 1) 3 4)"));
		
		// TODO: check if this should really fail? Probably yes, because expander shouldn't eval *address* in lookup?
		//assertCVMEquals(2,eval(ctx,"(*address*/fot (+ 1 1) 3 4)"));
	}

	
	@Test
	public void testStaticCompilation() {
		if (Constants.OPT_STATIC) {
			assertSame(CVMBool.TRUE,eval("(:static (lookup-meta 'count))"));
	
			// Static core function should compile to constant
			assertEquals(Constant.of(Core.COUNT),eval("(compile 'count)"));
	
			// Constant addresses should also get static compilation
			assertEquals(Constant.of(Core.TRANSFER),eval("(compile '#8/transfer)"));
		} else {
			assertEquals(Lookup.create(Address.create(8), Symbols.COUNT),eval("(compile 'count)"));
		}

		// Aliases that don't hit static definitions compile to dynamic lookup
		assertEquals(Lookup.create(Address.create(1), Symbols.COUNT),eval("(compile '#1/count)"));
		assertEquals(Lookup.create(Address.create(8888), Symbols.TRANSFER),eval("(compile '#8888/transfer)"));
	}
	
	@Test
	public void testBindingFormRegression() {
		// See #395, failure due to bad binding form
		assertCompileError(step("(defn foo [ok 42])"));
		assertCompileError(step("(defn foo [42 ok])"));
		
		// OK since 42 now interpreted as a syntax tag on the parameter "ok"
		assertNotError(step("(defn foo [^42 ok])")); 
	}

	@Test
	public void testEdgeCases() {
		assertFalse(evalB("(= *juice* *juice*)"));
		assertEquals(Maps.of(1L,2L),eval("{1 2 1 2}"));
		
		assertEquals(Maps.of(1L,5L),eval("{1 2 1 3 1 4 1 5}"));


		assertEquals(Maps.of(11L,5L),eval("{~((fn [x] (do (return (+ x 7)) 100)) 4) 5}"));
		assertEquals(Maps.of(1L,2L),eval("{(inc 0) 2}"));

		// TODO: sanity check? Does/should this depend on map ordering?
		assertEquals(1L,evalL("(count {~(inc 1) 3 ~(dec 3) 4})"));

		// TODO: figure out correct behaviour for this.
		assertEquals(1L,evalL("(count #{*juice* *juice* *juice* *juice*})"));
		assertEquals(1L,evalL("(count #{~*juice* ~*juice* ~*juice* ~*juice*})"));
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
