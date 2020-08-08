package convex.core.lang;

import convex.core.data.Syntax;
import org.junit.jupiter.api.Test;
import org.parboiled.Parboiled;
import org.parboiled.Rule;
import org.parboiled.errors.ParserRuntimeException;
import org.parboiled.parserunners.ReportingParseRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class Scrypt2Test {

    static final Context<?> CONTEXT = TestState.INITIAL_CONTEXT;

    static Scrypt2 scrypt() {
        return Parboiled.createParser(Scrypt2.class);
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
        Syntax syn = Scrypt.readSyntax(source);

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

        assertThrows(ParserRuntimeException.class, () -> parse(compilationUnit, ""));
        assertThrows(ParserRuntimeException.class, () -> parse(compilationUnit, "1 true"));
        assertThrows(ParserRuntimeException.class, () -> parse(compilationUnit, "{"));
        assertThrows(ParserRuntimeException.class, () -> parse(compilationUnit, "inc(1"));
        assertThrows(ParserRuntimeException.class, () -> parse(compilationUnit, "cond { }"));

        // Scalar Data Types
        assertEquals(Reader.read("1"), parse(compilationUnit, "1"));
        assertEquals(Reader.read("true"), parse(compilationUnit, "true"));
        assertEquals(Reader.read("false"), parse(compilationUnit, "false"));
        assertEquals(Reader.read("symbol"), parse(compilationUnit, "symbol"));
        assertEquals(Reader.read(":keyword"), parse(compilationUnit, ":keyword"));

        // Compound Data Types
        assertEquals(Reader.read("[]"), parse(compilationUnit, "[]"));
        assertEquals(Reader.read("{}"), parse(compilationUnit, "{}"));

        // Function Application
        assertEquals(Reader.read("(inc 1)"), parse(compilationUnit, "inc(1)"));
        assertEquals(Reader.read("(map inc [1,2])"), parse(compilationUnit, "map(inc, [1, 2])"));
        assertEquals(Reader.read("(inc (inc 1))"), parse(compilationUnit, "inc(inc(1))"));

        // Do Expression
        assertEquals(Reader.read("(do)"), parse(compilationUnit, "do { }"));
        assertEquals(Reader.read("(do 1 (inc 2) {:n 3})"), parse(compilationUnit, "do { 1 inc(2) {:n 3} }"));

        // Def Expression
        assertEquals(Reader.read("(def x 1)"), parse(compilationUnit, "def x = 1"));
        assertEquals(Reader.read("(def x (inc 1))"), parse(compilationUnit, "def x = inc(1)"));
        assertEquals(Reader.read("(def x (do (reduce + [] [1,2,3])))"), parse(compilationUnit, "def x = do { reduce(+, [], [1, 2, 3]) }"));

        // Cond Expression
        assertEquals(Reader.read("(cond false 1)"), parse(compilationUnit, "cond { false 1 }"));
        assertEquals(Reader.read("(cond (zero? x) 1)"), parse(compilationUnit, "cond { zero?(x) 1 }"));
        assertEquals(Reader.read("(cond false 1 true 2)"), parse(compilationUnit, "cond { false 1 true 2 }"));

    }

}
