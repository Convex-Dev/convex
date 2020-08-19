package convex.core.lang;

import convex.core.data.Syntax;
import org.junit.jupiter.api.Test;
import org.parboiled.Parboiled;
import org.parboiled.Rule;
import org.parboiled.errors.ParserRuntimeException;
import org.parboiled.parserunners.ReportingParseRunner;

import static org.junit.jupiter.api.Assertions.*;

public class ScryptNextTest {

    static final Context<?> CONTEXT = TestState.INITIAL_CONTEXT;

    static ScryptNext scrypt() {
        return Parboiled.createParser(ScryptNext.class);
    }

    @SuppressWarnings("rawtypes")
    static Object parse(Rule rule, String source) {
        var result = new ReportingParseRunner(rule).run(source);

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
    public static <T> Context<T> step(Context<?> c, String source) {
        Syntax syn = ScryptNext.readSyntax(source);

        Context<AOp<Object>> cctx = c.expandCompile(syn);

        if (cctx.isExceptional()) return (Context<T>) cctx;

        AOp<Object> op = cctx.getResult();

        return (Context<T>) c.run(op);
    }

    public static <T> Context<T> step(String source) {
        return step(CONTEXT, source);
    }

    @SuppressWarnings("unchecked")
    public static <T> T eval(String source) {
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
    public void testExpressions() {
        // Not allowed to start a symbol with '_'
        assertThrows(ParserRuntimeException.class, () -> parse("_"));
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
        assertEquals(Reader.read("nil"), parse("nil"));
        assertEquals(Reader.read("\"Hello\""), parse("\"Hello\""));
        assertEquals(Reader.read("1"), parse("1"));
        assertEquals(Reader.read("true"), parse("true"));
        assertEquals(Reader.read("false"), parse("false"));
        assertEquals(Reader.read("symbol"), parse("symbol"));
        assertEquals(Reader.read("symbol-abc"), parse("symbol_abc"));
        assertEquals(Reader.read("symbol-"), parse("symbol_"));
        assertEquals(Reader.read("symbol?"), parse("symbol?"));
        assertEquals(Reader.read("symbol!"), parse("symbol!"));
        assertEquals(Reader.read(":keyword"), parse(":keyword"));
        assertEquals(Reader.read(":keyword-abc"), parse(":keyword_abc"));

        // Compound Data Types
        assertEquals(Reader.read("[]"), parse("[]"));
        assertEquals(Reader.read("{}"), parse("{}"));
        assertEquals(Reader.read("{}"), parse("{};"));
        assertEquals(Reader.read("#{}"), parse("#{}"));

        // Def Statement
        assertEquals(Reader.read("(def x 1)"), parse("def x = 1;"));
        assertEquals(Reader.read("(def x 1)"), parse("def x = do { 1; };"));
        assertEquals(Reader.read("(def f (fn [x xs] (conj xs x)))"), parse("def f = (x, xs) -> conj(xs, x);"));
        assertEquals(Reader.read("(def x (inc 1))"), parse("def x = inc(1);"));
        assertEquals(Reader.read("(def x (reduce + 0 [1,2,3]))"), parse("def x = reduce(+, 0, [1, 2, 3]);"));
        assertEquals(Reader.read("(def x (reduce + 0 [1,2,3]))"), parse("def x = do { reduce(+, 0, [1, 2, 3]); };"));
        assertEquals(Reader.read("(def f (fn []))"), parse("def f = fn(){};"));

        // Defn Statement
        assertEquals(Reader.read("(def f (fn [x] x))"), parse("defn f(x) { x; }"));
        assertEquals(Reader.read("(def f (fn []))"), parse("defn f() { }"));
        assertEquals(Reader.read("(def f (fn [] nil))"), parse("defn f() { {} }"));
        assertEquals(Reader.read("(def f (fn [] {}))"), parse("defn f() { {}; }"));
        assertEquals(parse("def f = fn(x){ x; };"), parse("defn f(x) { x; }"));


        // TODO Let Statement
        //assertEquals(Reader.read("(set! x 1)"), parse("x = 1;"));
        //assertEquals(Reader.read("(set! x 1)"), parse("let x = 1;"));


        // If Else Statement
        assertEquals(Reader.read("(if true 1)"), parse("if(true, 1)"));
        assertEquals(Reader.read("(if true 1 2)"), parse("if(true, 1, 2)"));
        assertEquals(Reader.read("(cond true nil)"), parse("if (true) {}"));
        assertEquals(Reader.read("(cond true 1)"), parse("if (true) 1;"));
        assertEquals(Reader.read("(cond true 1 2)"), parse("if (true) 1; else 2;"));
        assertEquals(Reader.read("(cond true 1 2)"), parse("if (true) { 1; } else 2;"));
        assertEquals(Reader.read("(cond true 1 2)"), parse("if (true) { 1; } else { 2;} "));
        assertEquals(Reader.read("(cond true (do (cond true 1) 2))"), parse("if (true) { if (true) 1; 2;}"));

        // When Statement
        assertEquals(Reader.read("(cond true 1)"), parse("when (true) 1;"));
        assertEquals(Reader.read("(cond true 1)"), parse("when (true) { 1; }"));
        assertEquals(Reader.read("(cond true nil)"), parse("when (true) {}"));
        assertEquals(Reader.read("(cond true {})"), parse("when (true) {};"));
        assertEquals(Reader.read("(cond true {})"), parse("when (true) { {}; }"));
        assertEquals(Reader.read("(cond true nil)"), parse("when (true) ;"));
        assertEquals(Reader.read("(cond true (do (f 1) 2))"), parse("when (true) { f(1); 2; }"));

        // Function Expression
        assertEquals(Reader.read("(fn [])"), parse("fn ( ) { }"));
        assertEquals(Reader.read("(fn [x])"), parse("fn (x) { }"));
        assertEquals(Reader.read("(fn [x y])"), parse("fn (x, y) { }"));
        assertEquals(Reader.read("(fn [x] x)"), parse("fn (x) { x; }"));
        assertEquals(Reader.read("(fn [x y] x y)"), parse("fn (x, y) { x; y; }"));
        assertEquals(Reader.read("(fn [x] 1 {} [] (inc x))"), parse("fn (x) { 1; {}; []; inc(x); }"));

        // Lambda Expression
        assertEquals(Reader.read("(fn [] nil)"), parse("() -> nil"));
        assertEquals(Reader.read("(fn [x] x)"), parse("(x) -> x"));
        assertEquals(Reader.read("(fn [x xs] (conj xs x))"), parse("(x, xs) -> conj(xs, x)"));

        // Callable Expression
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
        assertEquals(Reader.read("1"), parse("1;"));
        assertEquals(Reader.read("(fn [])"), parse("fn(){};"));
        assertEquals(Reader.read("(fn [x] x)"), parse("(x) -> x;"));
        assertEquals(Reader.read("(do (def x 1) (def y 2))"), parse("def x = 1; def y = 2;"));
        assertEquals(Reader.read("(do (def x 1) (cond (zero? x) :zero :not-zero) 2)"), parse("def x = 1; if(zero?(x)) :zero; else :not_zero; 2;"));

        // Block Statement
        assertEquals(Reader.read("1"), parse("{ 1; }"));
        assertEquals(Reader.read("(do 1 (inc 2) {:n 3})"), parse("{ 1; inc(2); {:n 3}; }"));
        assertEquals(Reader.read("(do (def f (fn [x] x)) (f 1))"), parse("{ def f = fn(x) { x; }; f(1); }"));
        assertEquals(Reader.read("(do (def x? true) (cond x? (do 1 2)) 1)"), parse("do { def x? = true; if (x?) { 1; 2; } 1; }"));
        assertEquals(Reader.read("(do (def x? true) (cond x? 1))"), parse("do { def x? = true; when (x?) { 1; } }"));
        assertEquals(2, (Long) eval("{ inc(1); }"));

        // Do
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
        assertEquals(1, (Long) eval("do { 1; }"));
    }

}
