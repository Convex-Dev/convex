package convex.core.lang;

import convex.core.data.ACell;
import convex.core.data.Syntax;
import org.junit.jupiter.api.Test;
import org.parboiled.Parboiled;
import org.parboiled.Rule;
import org.parboiled.errors.ParserRuntimeException;
import org.parboiled.parserunners.ReportingParseRunner;

import static convex.test.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

public class ScryptNextTest {

    static final Context<?> CONTEXT = TestState.INITIAL_CONTEXT.fork();

    static ScryptNext scrypt() {
        return Parboiled.createParser(ScryptNext.class);
    }

    static Object parse(Rule rule, String source) {
        var result = new ReportingParseRunner<ACell>(rule).run(source);

        if (result.matched) {
            return Syntax.unwrapAll(result.resultValue);
        } else {
            throw new RuntimeException(rule.toString() + " failed to match " + source);
        }
    }

    static Object parse(String source) {
        return parse(scrypt().CompilationUnit(), source);
    }

    @SuppressWarnings("unchecked")
    public static <T extends ACell> Context<T> step(Context<?> c, String source) {
        c=c.fork();
    	Syntax syn = ScryptNext.readSyntax(source);

        Context<AOp<ACell>> cctx = c.expandCompile(syn);

        if (cctx.isExceptional()) return (Context<T>) cctx;

        AOp<ACell> op = cctx.getResult();

        return (Context<T>) c.run(op);
    }

    public static <T extends ACell> Context<T> step(String source) {
        return step(CONTEXT, source);
    }

    @SuppressWarnings("unchecked")
    public static <T extends ACell> T eval(String source) {
        return (T) step(CONTEXT, source).getResult();
    }

    @Test
    public void testArithmetic() {
        assertEquals(Reader.read("(+ 1 2)"), parse("1 + 2"));
        assertEquals(Reader.read("(+ (+ 1 2) 3)"), parse("1 + 2 + 3"));
        assertEquals(Reader.read("(* 1 2)"), parse("1 * 2"));
        assertEquals(Reader.read("(* (* 1 2) 3)"), parse(scrypt().Arithmetic2Expression() , "1 * 2 * 3"));
        assertEquals(Reader.read("(+ 1 (* 2 3))"), parse("1 + 2 * 3"));
        assertEquals(Reader.read("(+ (- 9 2) 3)"), parse("9 - 2 + 3"));
        assertEquals(Reader.read("(- 9 (* 2 3))"), parse("9 - 2 * 3"));
    }

    @Test
    public void testSymbolTransform() {
        assertEquals("*a", scrypt().convexLispSymbol("_a").getName().toString());
        assertEquals("a*", scrypt().convexLispSymbol("a_").getName().toString());
        assertEquals("*a*", scrypt().convexLispSymbol("_a_").getName().toString());
        assertEquals("*a-b*", scrypt().convexLispSymbol("_a_b_").getName().toString());
    }

    @Test
    public void testCall() {
        assertEquals(Reader.read("(call \"<Address>\" (do-stuff 1 2))"), parse("call \"<Address>\" do_stuff(1, 2)"));
        assertEquals(Reader.read("(call \"<Address>\" (do-stuff {:n 1}))"), parse("call \"<Address>\" do_stuff({:n 1})"));
        assertEquals(Reader.read("(call \"<Address>\" (inc x) (buy \"Something\"))"), parse("call \"<Address>\" offer inc(x) buy(\"Something\")"));
        assertEquals(Reader.read("(call \"<Address>\" (+ 1 (* 2 3)) (buy \"Something\"))"), parse("call \"<Address>\" offer 1 + (2 * 3) buy(\"Something\")"));

        // ErrorValue[:STATE] : Actor does not exist
        assertStateError(step("call 6666666 buy(\"Something\")"));

        // Can't call without a function name
        assertThrows(ParserRuntimeException.class, () -> parse("call \"ABC\""));
        // Can't call without args
        assertThrows(ParserRuntimeException.class, () -> parse("call \"ABC\" do_stuff"));
        // Can't call without an address
        assertThrows(ParserRuntimeException.class, () -> parse("call do_stuff(1, 2)"));
        // Can't offer without amount
        assertThrows(ParserRuntimeException.class, () -> parse("call \"<Address>\" offer do_stuff(1, 2)"));
    }

    @Test
    public void testExpressions() {
        // Not allowed to start a symbol with '&'
        assertThrows(ParserRuntimeException.class, () -> parse("&"));

        // 'x+y' is not a valid Scrypt symbol - it's a valid *Convex Lisp* symbol though.
        assertThrows(ParserRuntimeException.class, () -> parse("def x+y = 1;"));
        // '-' in the middle of the name is invalid, an underscore '_' must be used instead.
        assertThrows(ParserRuntimeException.class, () -> parse("invalid-symbol-"));

        // Body must be an expression
        assertThrows(ParserRuntimeException.class, () -> parse("() ->"));
        // Args must be symbols
        assertThrows(ParserRuntimeException.class, () -> parse("(1) -> x"));
        // Args must be wrapped in parenthesis
        assertThrows(ParserRuntimeException.class, () -> parse("x, y -> x"));

        // Multiple expressions are not valid
        assertThrows(ParserRuntimeException.class, () -> parse("1 true"));

        // Missing closing '}'
        assertThrows(ParserRuntimeException.class, () -> parse("{"));

        // Requires binding
        assertThrows(ParserRuntimeException.class, () -> parse("def x"));
        // Expression must be followed by ';'
        assertThrows(ParserRuntimeException.class, () -> parse("def x = (x) -> x"));
        // Can't def using a block statement
        assertThrows(ParserRuntimeException.class, () -> parse("def x = { 1; }"));
        // Can't def using a when statement
        assertThrows(ParserRuntimeException.class, () -> parse("def x = when (test) 1;"));
        // Can't def using an if statement
        assertThrows(ParserRuntimeException.class, () -> parse("def x = if (test) 1;"));
        // Can't def using a nested def statement
        assertThrows(ParserRuntimeException.class, () -> parse("def x = def y = 1"));

        // Missing ')'
        assertThrows(ParserRuntimeException.class, () -> parse("inc(1"));
        // Missing expression after '+'
        assertThrows(ParserRuntimeException.class, () -> parse("1 +"));

        // Scalar Data Types
        // =====================
        assertEquals(Reader.read("nil"), parse("nil"));
        assertEquals(Reader.read("\"Hello\""), parse("\"Hello\""));
        assertEquals(Reader.read("1"), parse("1"));
        assertEquals(Reader.read("true"), parse("true"));
        assertEquals(Reader.read("false"), parse("false"));
        assertEquals(Reader.read("*balance*"), parse("_balance_"));
        assertEquals(Reader.read("symbol"), parse("symbol"));
        assertEquals(Reader.read("symbol-abc"), parse("symbol_abc"));
        assertEquals(Reader.read("symbol*"), parse("symbol_"));
        assertEquals(Reader.read("symbol?"), parse("symbol?"));
        assertEquals(Reader.read("symbol!"), parse("symbol!"));
        assertEquals(Reader.read(":keyword"), parse(":keyword"));
        assertEquals(Reader.read(":keyword-abc"), parse(":keyword_abc"));

        // Compound Data Types
        // =====================
        assertEquals(Reader.read("[]"), parse("[]"));
        assertEquals(Reader.read("{}"), parse("{}"));
        assertEquals(Reader.read("{}"), parse("{};"));
        assertEquals(Reader.read("#{}"), parse("#{}"));

        // Def Statement
        // =====================
        assertEquals(Reader.read("(def x 1)"), parse("def x = 1;"));

        // Missing semicolon
        assertThrows(ParserRuntimeException.class, () -> parse("def x = 1"));

        assertEquals(Reader.read("(def x 1)"), parse("def x = do { 1; };"));
        assertEquals(Reader.read("(def f (fn [x xs] (conj xs x)))"), parse("def f = (x, xs) -> conj(xs, x);"));
        assertEquals(Reader.read("(def x (inc 1))"), parse("def x = inc(1);"));
        assertEquals(Reader.read("(def x (reduce + 0 [1,2,3]))"), parse("def x = reduce(+, 0, [1, 2, 3]);"));
        assertEquals(Reader.read("(def x (reduce + 0 [1,2,3]))"), parse("def x = do { reduce(+, 0, [1, 2, 3]); };"));
        assertEquals(Reader.read("(def f (fn []))"), parse("def f = fn(){};"));

        // Defn Statement
        // =====================
        assertEquals(Reader.read("(def f (fn [x] x))"), parse("defn f(x) { x; }"));

        // Missing semicolon after x
        assertThrows(ParserRuntimeException.class, () -> parse("defn f(x) { x }"));

        assertEquals(Reader.read("(def f (fn []))"), parse("defn f() { }"));
        assertEquals(Reader.read("(def f (fn [] nil))"), parse("defn f() { {} }"));
        assertEquals(Reader.read("(def f (fn [] {}))"), parse("defn f() { {}; }"));
        assertEquals(parse("def f = fn(x){ x; };"), parse("defn f(x) { x; }"));


        // TODO Let Statement
        //assertEquals(Reader.read("(set! x 1)"), parse("x = 1;"));
        //assertEquals(Reader.read("(set! x 1)"), parse("let x = 1;"));


        // If Else Statement
        // =====================
        assertEquals(Reader.read("(if true 1)"), parse("if(true, 1)"));
        assertEquals(Reader.read("(if true 1 2)"), parse("if(true, 1, 2)"));
        assertEquals(Reader.read("(cond true nil)"), parse("if (true) {}"));
        assertEquals(Reader.read("(cond true 1)"), parse("if (true) 1;"));
        assertEquals(Reader.read("(cond true 1 2)"), parse("if (true) 1; else 2;"));
        assertEquals(Reader.read("(cond true 1 2)"), parse("if (true) { 1; } else 2;"));
        assertEquals(Reader.read("(cond true 1 2)"), parse("if (true) { 1; } else { 2;} "));
        assertEquals(Reader.read("(cond true (do (cond true 1) 2))"), parse("if (true) { if (true) 1; 2;}"));

        // When Statement
        // =====================
        assertEquals(Reader.read("(cond true 1)"), parse("when (true) 1;"));
        assertEquals(Reader.read("(cond true 1)"), parse("when (true) { 1; }"));
        assertEquals(Reader.read("(cond true nil)"), parse("when (true) {}"));
        assertEquals(Reader.read("(cond true {})"), parse("when (true) {};"));
        assertEquals(Reader.read("(cond true {})"), parse("when (true) { {}; }"));
        assertEquals(Reader.read("(cond true nil)"), parse("when (true) ;"));
        assertEquals(Reader.read("(cond true (do (f 1) 2))"), parse("when (true) { f(1); 2; }"));

        // Function Expression
        // =====================
        assertEquals(Reader.read("(fn [])"), parse("fn ( ) { }"));
        assertEquals(Reader.read("(fn [x])"), parse("fn (x) { }"));
        assertEquals(Reader.read("(fn [x y])"), parse("fn (x, y) { }"));
        assertEquals(Reader.read("(fn [x] x)"), parse("fn (x) { x; }"));
        assertEquals(Reader.read("(fn [x y] x y)"), parse("fn (x, y) { x; y; }"));
        assertEquals(Reader.read("(fn [x] 1 {} [] (inc x))"), parse("fn (x) { 1; {}; []; inc(x); }"));

        // Lambda Expression
        // =====================
        assertEquals(Reader.read("(fn [] nil)"), parse("() -> nil"));
        assertEquals(Reader.read("(fn [x] x)"), parse("(x) -> x"));
        assertEquals(Reader.read("(fn [x xs] (conj xs x))"), parse("(x, xs) -> conj(xs, x)"));

        // Callable Expression
        // =====================
        assertEquals(Reader.read("(f)"), parse("f()"));
        assertEquals(Reader.read("([] 0)"), parse("[](0)"));
        assertEquals(Reader.read("({} :key)"), parse("{}(:key)"));
        assertEquals(Reader.read("(#{} x)"), parse("#{}(x)"));
        assertEquals(Reader.read("((fn [] nil))"), parse("fn(){;}()"));
        assertEquals(Reader.read("((fn [x] x) 1)"), parse("fn(x){x;}(1)"));
        assertEquals(Reader.read("((f))"), parse("f()()"));
        assertEquals(Reader.read("((f 1) 2)"), parse("f(1)(2)"));
        assertEquals(Reader.read("(((f 1) 2 3) 4 5)"), parse("f(1)(2, 3)(4, 5)"));
        assertEquals(Reader.read("(inc 1)"), parse("inc(1)"));
        assertEquals(Reader.read("(inc (inc 1))"), parse("inc(inc(1))"));
        assertEquals(Reader.read("(map inc [1,2])"), parse("map(inc, [1, 2])"));
        assertEquals(Reader.read("(map (fn [x] x) [1,2])"), parse("map(fn(x){x;}, [1, 2])"));
        assertEquals(Reader.read("(map (fn [x] x) [1,2])"), parse("map((x) -> x, [1, 2])"));
        assertEquals(Reader.read("(reduce (fn [acc x] (+ acc x)) 0 [1 2 3])"), parse("reduce(fn(acc, x){ acc + x; }, 0, [1, 2, 3])"));
        assertEquals(Reader.read("(reduce (fn [acc x] (+ acc x)) 0 [1 2 3])"), parse("reduce((acc, x) -> acc + x , 0, [1, 2, 3])"));
        assertEquals(Reader.read("(reduce + 0 [1 2 3])"), parse("reduce(+, 0, [1, 2, 3])"));

        // Statements
        // =====================
        assertEquals(Reader.read("1"), parse("1;"));
        assertEquals(Reader.read("(fn [])"), parse("fn(){};"));
        assertEquals(Reader.read("(fn [x] x)"), parse("(x) -> x;"));
        assertEquals(Reader.read("(do (def x 1) (def y 2))"), parse("def x = 1; def y = 2;"));
        assertEquals(Reader.read("(do (def x 1) (cond (zero? x) :zero :not-zero) 2)"), parse("def x = 1; if(zero?(x)) :zero; else :not_zero; 2;"));

        // Block Statement
        // =====================

        // Missing semicolon
        assertThrows(ParserRuntimeException.class, () -> parse("{ 1 }"));

        assertEquals(Reader.read("1"), parse("{ 1; }"));
        assertEquals(Reader.read("(do 1 (inc 2) {:n 3})"), parse("{ 1; inc(2); {:n 3}; }"));
        assertEquals(Reader.read("(do (def f (fn [x] x)) (f 1))"), parse("{ def f = fn(x) { x; }; f(1); }"));
        assertEquals(Reader.read("(do (def x? true) (cond x? (do 1 2)) 1)"), parse("do { def x? = true; if (x?) { 1; 2; } 1; }"));
        assertEquals(Reader.read("(do (def x? true) (cond x? 1))"), parse("do { def x? = true; when (x?) { 1; } }"));
        assertCVMEquals(2, eval("{ inc(1); }"));

        // Do
        // =====================
        assertEquals(Reader.read("(do 1 2)"), parse("do(1, 2)"));
        assertEquals(Reader.read("nil"), parse("do { }"));
        assertEquals(Reader.read("nil"), parse("do { };"));
        assertEquals(Reader.read("{}"), parse("do { {}; }"));
        assertEquals(Reader.read("nil"), parse("do { {} }"));
        assertEquals(Reader.read("1"), parse("do { 1; }"));
        assertEquals(Reader.read("1"), parse("do { 1; };"));
        assertEquals(Reader.read("(do 1 (inc 2) {:n 3})"), parse("do { 1; inc(2); {:n 3}; }"));
        assertEquals(Reader.read("(do (def f (fn [x] x)) (f 1))"), parse("do { def f = fn(x) { x; }; f(1); }"));
        assertNull(eval("do { }"));
        assertCVMEquals(1, eval("do { 1; }"));
    }

}
