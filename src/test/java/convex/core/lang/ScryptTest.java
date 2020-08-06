package convex.core.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import convex.core.data.*;
import org.junit.jupiter.api.Test;
import org.parboiled.Parboiled;
import org.parboiled.Rule;
import org.parboiled.parserunners.ReportingParseRunner;

import convex.core.Init;
import convex.core.exceptions.ParseException;

public class ScryptTest {

    static final Context<?> CON = TestState.INITIAL_CONTEXT;

    static Scrypt scrypt() {
        return Parboiled.createParser(Scrypt.class);
    }

    @SuppressWarnings("rawtypes")
    static ReportingParseRunner runner(Rule rule) {
        return new ReportingParseRunner(rule);
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

        Context<T> rctx = (Context<T>) c.run(op);
        return rctx;
    }

    public static <T> Context<T> step(String source) {
        return step(CON, source);
    }

    @SuppressWarnings("unchecked")
    public static <T> T eval(String source) {
        return (T) step(CON, source).getResult();
    }

    @SuppressWarnings("rawtypes")
    @Test
    public void testInfixOperator() {
        var parser = Parboiled.createParser(Scrypt.class);

        // +
        {
            var result = new ReportingParseRunner(parser.InfixOperator()).run("+");

            assertTrue(result.matched);
            assertSame(Symbols.PLUS, result.resultValue);
        }

        {
            assertFalse(new ReportingParseRunner(parser.InfixOperator()).run(" + ").matched);
        }

        // -
        {
            var result = new ReportingParseRunner(parser.InfixOperator()).run("-");

            assertTrue(result.matched);
            assertSame(Symbols.MINUS, result.resultValue);
        }

        {
            assertFalse(new ReportingParseRunner(parser.InfixOperator()).run(" - ").matched);
        }


        // *
        {
            var result = new ReportingParseRunner(parser.InfixOperator()).run("*");

            assertTrue(result.matched);
            assertSame(Symbols.TIMES, result.resultValue);
        }

        {
            assertFalse(new ReportingParseRunner(parser.InfixOperator()).run(" * ").matched);
        }


        // /
        {
            var result = new ReportingParseRunner(parser.InfixOperator()).run("/");

            assertTrue(result.matched);
            assertSame(Symbols.DIVIDE, result.resultValue);
        }

        {
            assertFalse(new ReportingParseRunner(parser.InfixOperator()).run(" / ").matched);
        }
    }

    @SuppressWarnings("rawtypes")
    @Test
    public void testNestedExpression() {
        var parser = Parboiled.createParser(Scrypt.class);

        // Nested without an infix expression.
        {
            var result = new ReportingParseRunner(parser.NestedExpression()).run("( 1 )");
            var syn = (Syntax) result.resultValue;

            assertTrue(result.matched);
            assertEquals(1L, (Long) syn.getValue());
        }

        // Nested Expression inside Nested Expression.
        {
            var result = new ReportingParseRunner(parser.NestedExpression()).run("( ( 1 ) )");
            var syn = (Syntax) result.resultValue;

            assertTrue(result.matched);
            assertEquals(1L, (Long) syn.getValue());
        }

        // Space is not allowed before/after parenthesis - although it's allowed after/before parenthesis.
        {
            var result = new ReportingParseRunner(parser.NestedExpression()).run(" ( 1 ) ");

            assertFalse(result.matched);
        }

        {
            var result = new ReportingParseRunner(parser.NestedExpression()).run("(1 + 2)");
            var syn = (Syntax) result.resultValue;
            var value = (List) syn.getValue();

            assertTrue(result.matched);
            assertEquals(3, value.count());
            assertSame(Symbols.PLUS, ((Syntax) value.get(0)).getValue());
            assertEquals(1L, (Long) ((Syntax) value.get(1)).getValue());
            assertEquals(2L, (Long) ((Syntax) value.get(2)).getValue());
        }

    }

    @Test
    public void testFunctionApplication() {
        var parser = Parboiled.createParser(Scrypt.class);

        {
            assertEquals(
                    Reader.read("(f)"),
                    parse(parser.FunctionApplication(), "f()"));

            assertEquals(
                    Reader.read("(f 1)"),
                    parse(parser.FunctionApplication(), "f(1)")
            );

            assertEquals(
                    Reader.read("(f 1)"),
                    parse(parser.FunctionApplication(), "f( 1 )")
            );

            assertEquals(
                    Reader.read("(f 1 2)"),
                    parse(parser.FunctionApplication(), "f(1, 2)")
            );

            assertEquals(
                    Reader.read("(f 1 (+ 2 3))"),
                    parse(parser.FunctionApplication(), "f(1, 2 + 3)")
            );

            assertEquals(
                    Reader.read("(f 1 (* (+ 2 3) 4))"),
                    parse(parser.FunctionApplication(), "f(1, (2 + 3) * 4)")
            );

            assertEquals(
                    Reader.read("(if true 1)"),
                    parse(parser.FunctionApplication(), "if(true, 1)")
            );

            assertEquals(1L, (Long) eval("if(true, 1)"));

            assertEquals(2L, (Long) eval("(identity(inc))(1)"));
        }
    }

    @Test
    public void testExpressionRule() {
        assertNull(eval("nil"));
        assertEquals(1L, (Long) eval("1"));
        assertEquals("Foo", eval("\"Foo\""));
        assertEquals(true, eval("true"));
        assertEquals(Keyword.create("keyword"), eval(":keyword"));
        assertSame(Core.MAP, eval("map"));
    }

    @Test
    public void testConstant() {
        assertEquals((Long) Syntax.create(1L).getValue(), Scrypt.readSyntax("1").getValue());
        assertEquals((Long) Syntax.create(1L).getValue(), Scrypt.readSyntax(" 1").getValue());
        assertEquals((Long) Syntax.create(1L).getValue(), Scrypt.readSyntax("1  ").getValue());
        assertEquals((Long) Syntax.create(1L).getValue(), Scrypt.readSyntax("\t1\n").getValue());
    }

    @Test
    public void testInfix() {
        // -- Arithmetic Operators

        assertEquals(2L, (Long) eval("1 + 1"));
        assertEquals(3L, (Long) eval("1+1+1"));
        assertEquals(3L, (Long) eval("1 + 1 + 1"));
        assertEquals(3L, (Long) eval("\t1 + \n1 + \t1"));

        assertEquals(0L, (Long) eval("1 - 1"));

        assertEquals(7L, (Long) eval("1 + (2*3)"));
        assertEquals(7L, (Long) eval("1 + \n(2 \t* \n3)"));

        assertEquals(1L, (Long) eval("1 * 1"));

        assertEquals(1.0, eval("1 / 1"));
    }

    @Test
    public void testLiteral() {
        assertEquals(1L, (Long) Scrypt.readSyntax("1").getValue());
        assertEquals(true, Scrypt.readSyntax("true").getValue());
        assertEquals(Keyword.create("k"), Scrypt.readSyntax(":k").getValue());
        assertEquals("Foo", Scrypt.readSyntax("\"Foo\"").getValue());
        assertNull(Scrypt.readSyntax("nil").getValue());
    }

    @Test
    public void testInvalid() {
        assertThrows(ParseException.class, () -> Scrypt.readSyntax("").getValue());
        assertThrows(ParseException.class, () -> Scrypt.readSyntax("(def a 1)").getValue());
    }

    @Test
    public void testSymbol() {
        assertEquals(Init.HERO, eval("*address*"));
        assertSame(Core.MAP, eval("map"));
    }

    @Test
    public void testVector() {
        assertEquals(Vectors.empty(), eval("[]"));
        assertEquals(Vectors.of(1L), eval("[1]"));
        assertEquals(Vectors.of(1L), eval("[ 1 ]"));
        assertEquals(Vectors.of(1L, 2L), eval("[1, 2]"));
        assertEquals(Vectors.of(3L), eval("[1 + 2]"));
        assertEquals(Vectors.of(3L, 3L), eval("[1 + 2, 3]"));
        assertEquals(Vectors.of(3L), eval("[1 + 2,]"));
        assertEquals(Vectors.of(3L, 3L), eval("[1 + 2, 3]"));
    }

    @Test
    public void testMap() {
        var scrypt = scrypt();

        var mapEntry = scrypt.MapEntry();

        assertEquals(Reader.read("[:x, 1]"), parse(mapEntry, ":x 1"));
        assertEquals(Reader.read("[[1 2], 3]"), parse(mapEntry, "[1, 2] 3"));

        var map = scrypt.MapLiteralExpression();

        assertEquals(Reader.read("{}"), parse(map, "{}"));
        assertEquals(Reader.read("{:x 1 :y 2 :z 3}"), parse(map, "{:x 1, :y 2, :z 3}"));
        assertEquals(Reader.read("{{} 1 [] 2}"), parse(map, "{{} 1, [] 2}"));
        assertThrows(ParseException.class, () -> eval("{1 2 3 4}"));
    }

    @Test
    public void testBlock() {
        var scrypt = scrypt();
        var block = scrypt.BlockExpression();

        assertEquals(Reader.read("(do 1)"), parse(block, "{ 1; }"));
        assertEquals(Reader.read("(do 1 :key [] {} (inc 1))"), parse(block, "{ 1; :key; []; {}; inc(1);}"));

        assertEquals(Maps.empty(), eval("{}"));
        assertEquals(1, (Long) eval("{1;}"));
    }

    @Test
    public void testDef() {
        var scrypt = scrypt();
        var def = scrypt.DefStatement();

        assertEquals(Reader.read("(def x 1)"), parse(def, "def x = 1;"));;

        assertEquals(1, (Long) eval("def x = 1;"));
        assertEquals(2, (Long) eval("{def x = 1; x + 1;}"));

        assertEquals(2, (Long) eval("(inc)(1)"));

        // Explain this
        assertThrows(ParseException.class, () -> eval("{1;2}"));
    }

}
