package convex.core.lang;

import convex.core.data.Maps;
import convex.core.data.Syntax;
import org.junit.jupiter.api.Test;
import org.parboiled.Parboiled;
import org.parboiled.Rule;
import org.parboiled.errors.ParserRuntimeException;
import org.parboiled.parserunners.ReportingParseRunner;

import static org.junit.jupiter.api.Assertions.*;

public class Scrypt3Test {

    static final Context<?> CONTEXT = TestState.INITIAL_CONTEXT;

    static Scrypt3 scrypt() {
        return Parboiled.createParser(Scrypt3.class);
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

    @SuppressWarnings("unchecked")
    public static <T> Context<T> step(Context<?> c, String source) {
        Syntax syn = Scrypt3.readSyntax(source);

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
    public void testExpressions() {
        var scrypt = scrypt();

        var compilationUnit = scrypt.CompilationUnit();

        assertThrows(ParserRuntimeException.class, () -> parse(compilationUnit, "1 true"));
        assertThrows(ParserRuntimeException.class, () -> parse(compilationUnit, "{"));
        assertThrows(ParserRuntimeException.class, () -> parse(compilationUnit, "def x"));
        assertThrows(ParserRuntimeException.class, () -> parse(compilationUnit, "inc(1"));
        assertThrows(ParserRuntimeException.class, () -> parse(compilationUnit, "cond { }"));

        // Scalar Data Types
        assertEquals(Reader.read("nil"), parse(compilationUnit, "nil"));
        assertEquals(Reader.read("\"Hello\""), parse(compilationUnit, "\"Hello\""));
        assertEquals(Reader.read("1"), parse(compilationUnit, "1"));
        assertEquals(Reader.read("true"), parse(compilationUnit, "true"));
        assertEquals(Reader.read("false"), parse(compilationUnit, "false"));
        assertEquals(Reader.read("symbol"), parse(compilationUnit, "symbol"));
        assertEquals(Reader.read(":keyword"), parse(compilationUnit, ":keyword"));

        // Compound Data Types
        assertEquals(Reader.read("[]"), parse(compilationUnit, "[]"));
        assertEquals(Reader.read("{}"), parse(compilationUnit, "{}"));
        assertEquals(Reader.read("{}"), parse(compilationUnit, "{};"));
        assertEquals(Reader.read("#{}"), parse(compilationUnit, "#{}"));

        // Block Expression
        assertEquals(Reader.read("(do 1)"), parse(compilationUnit, "{ 1; }"));
        assertEquals(Reader.read("(do 1 (inc 2) {:n 3})"), parse(compilationUnit, "{ 1; inc(2); {:n 3}; }"));
        assertEquals(Reader.read("(do (def f (fn [x] x)) (f 1))"), parse(compilationUnit, "{ def f = fn(x) { x; }; f(1); }"));
        assertEquals(Reader.read("(do (def x? true) (if x? (do 1 2)) 1)"), parse(compilationUnit, "do { def x? = true; if (x?) { 1; 2; } 1; }"));
        assertEquals(Reader.read("(do (def x? true) (when x? 1) 1)"), parse(compilationUnit, "do { def x? = true; when (x?) { 1; } 1; }"));
        assertEquals(2, (Long) eval("{ inc(1); }"));

        // Do Expression
        assertEquals(Reader.read("(do)"), parse(compilationUnit, "do { }"));
        assertEquals(Reader.read("(do 1)"), parse(compilationUnit, "do { 1; }"));
        assertEquals(Reader.read("(do 1 (inc 2) {:n 3})"), parse(compilationUnit, "do { 1; inc(2); {:n 3}; }"));
        assertEquals(Reader.read("(do (def f (fn [x] x)) (f 1))"), parse(compilationUnit, "do { def f = fn(x) { x; }; f(1); }"));
        assertNull(eval("do { }"));
        assertEquals(1, (Long) eval("do { 1; }"));

        // Def Expression
        assertEquals(Reader.read("(def x 1)"), parse(compilationUnit, "def x = 1"));
        assertEquals(Reader.read("(def f (fn [x xs] (conj xs x)))"), parse(compilationUnit, "def f = (x, xs) -> conj(xs, x)"));

        // TODO Do we want to allow this?
        assertEquals(Reader.read("(def x (def y 1))"), parse(compilationUnit, "def x = def y = 1"));

        assertEquals(Reader.read("(def x (inc 1))"), parse(compilationUnit, "def x = inc(1)"));
        assertEquals(Reader.read("(def x (do (reduce + [] [1,2,3])))"), parse(compilationUnit, "def x = do { reduce(+, [], [1, 2, 3]); }"));
        assertEquals(Reader.read("(def f (fn []))"), parse(compilationUnit, "def f = fn(){}"));

        // Cond Expression
        assertEquals(Reader.read("(cond false 1)"), parse(compilationUnit, "cond { false 1 }"));
        assertEquals(Reader.read("(cond (zero? x) 1)"), parse(compilationUnit, "cond { zero?(x) 1 }"));
        assertEquals(Reader.read("(cond false 1 (inc 1) 2)"), parse(compilationUnit, "cond { false 1,  inc(1) 2 }"));
        assertEquals(2, (Long) eval("cond { false 1, :default 2 }"));
        assertEquals(2, (Long) eval("cond { true inc(1) }"));
        assertEquals(2, (Long) eval("cond { false 1, :default 2 }"));
        assertEquals(2, (Long) eval("cond { false 1,  inc(1) 2 }"));
        assertNull(eval("cond { false 1,  nil 2 }"));

        // When Expression
        assertEquals(Reader.read("(when true 1)"), parse(compilationUnit, "when (true) { 1; }"));
        assertEquals(Reader.read("(when true)"), parse(compilationUnit, "when (true) {}"));
        assertEquals(Reader.read("(when true (f 1) 2)"), parse(compilationUnit, "when (true) { f(1); 2; }"));

        // If Else Expression
        assertEquals(Reader.read("(if true 1)"), parse(compilationUnit, "if (true) 1"));
        assertEquals(Reader.read("(if true 1 2)"), parse(compilationUnit, "if (true) 1 else 2"));
        assertEquals(Reader.read("(if true (do 1 2))"), parse(compilationUnit, "if (true) { 1; 2; }"));
        assertEquals(Reader.read("(if true (do 1 2) (do 3 4))"), parse(compilationUnit, "if (true) { 1; 2; } else { 3; 4; }"));
        assertSame(Maps.empty(), eval("if (true) {}"));

        // Function Expression
        assertEquals(Reader.read("(fn [])"), parse(compilationUnit, "fn ( ) { }"));
        assertEquals(Reader.read("(fn [x])"), parse(compilationUnit, "fn (x) { }"));
        assertEquals(Reader.read("(fn [x y])"), parse(compilationUnit, "fn (x, y) { }"));
        assertEquals(Reader.read("(fn [x] x)"), parse(compilationUnit, "fn (x) { x; }"));
        assertEquals(Reader.read("(fn [x y] x y)"), parse(compilationUnit, "fn (x, y) { x; y; }"));
        assertEquals(Reader.read("(fn [x] 1 {} [] (inc x))"), parse(compilationUnit, "fn (x) { 1; {}; []; inc(x); }"));

        // Lambda Expression
        assertEquals(Reader.read("(fn [] nil)"), parse(compilationUnit, "() -> nil"));
        assertEquals(Reader.read("(fn [x] x)"), parse(compilationUnit, "(x) -> x"));
        assertEquals(Reader.read("(fn [x xs] (conj xs x))"), parse(compilationUnit, "(x, xs) -> conj(xs, x)"));

        // Callable Expression
        assertEquals(Reader.read("(f)"), parse(compilationUnit, "f()"));
        assertEquals(Reader.read("([] 0)"), parse(compilationUnit, "[](0)"));
        assertEquals(Reader.read("({} :key)"), parse(compilationUnit, "{}(:key)"));
        assertEquals(Reader.read("(#{} x)"), parse(compilationUnit, "#{}(x)"));
        assertEquals(Reader.read("((fn [] nil))"), parse(compilationUnit, "fn(){;}()"));
        assertEquals(Reader.read("((fn [x] x) 1)"), parse(compilationUnit, "fn(x){x;}(1)"));
        assertEquals(Reader.read("(inc 1)"), parse(compilationUnit, "inc(1)"));
        assertEquals(Reader.read("(inc (inc 1))"), parse(compilationUnit, "inc(inc(1))"));
        assertEquals(Reader.read("(map inc [1,2])"), parse(compilationUnit, "map(inc, [1, 2])"));
        assertEquals(Reader.read("(map (fn [x] x) [1,2])"), parse(compilationUnit, "map(fn(x){x;}, [1, 2])"));
        assertEquals(Reader.read("(map (fn [x] x) [1,2])"), parse(compilationUnit, "map((x) -> x, [1, 2])"));
        assertEquals(Reader.read("(reduce (fn [acc,x] (conj acc x)) [] [1,2])"), parse(compilationUnit, "reduce(fn(acc, x){ conj(acc, x); }, [], [1, 2])"));
        assertEquals(Reader.read("(reduce (fn [acc,x] (conj acc x)) [] [1,2])"), parse(compilationUnit, "reduce((acc, x) -> conj(acc, x), [], [1, 2])"));

        // Statements
        assertEquals(Reader.read("(do (def x 1) (def y 2))"), parse(compilationUnit, "def x = 1; def y = 2;"));
        assertEquals(Reader.read("(do (def x 1) (if (zero? x) :zero :not-zero) nil 2)"), parse(compilationUnit, "def x = 1; if(zero?(x)) :zero else :not-zero; 2;"));


        /* TODO

        1 + inc(5) / 2
        (1 + inc(5)) / 2

        defn identity(x) {
          x
        }

        let (x 1 y 2) {
          x + y
        }

        */

    }

}
