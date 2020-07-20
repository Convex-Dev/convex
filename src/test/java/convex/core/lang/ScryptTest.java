package convex.core.lang;

import convex.core.Init;
import convex.core.data.*;
import convex.core.exceptions.ParseException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ScryptTest {

    static final Context<?> CON = TestState.INITIAL_CONTEXT;

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

    @Test
    public void testConstant() {
        assertEquals((Long) Syntax.create(1L).getValue(), Scrypt.readSyntax("1").getValue());
        assertEquals((Long) Syntax.create(1L).getValue(), Scrypt.readSyntax(" 1").getValue());
        assertEquals((Long) Syntax.create(1L).getValue(), Scrypt.readSyntax("1  ").getValue());
        assertEquals((Long) Syntax.create(1L).getValue(), Scrypt.readSyntax("\t1\n").getValue());
    }

    @Test
    public void testInfix() {
        assertEquals(2L, (Long) eval("1 + 1"));
        assertEquals(3L, (Long) eval("1+1+1"));
        assertEquals(3L, (Long) eval("1 + 1 + 1"));
        assertEquals(3L, (Long) eval("\t1 + \n1 + \t1"));
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
        assertEquals(Vectors.of(3L), eval("[1 + 2]"));
        assertEquals(Vectors.of(3L, 3L), eval("[1 + 2, 3]"));
        assertEquals(Vectors.of(3L), eval("[1 + 2,]"));
        assertEquals(Vectors.of(3L, 3L), eval("[1 + 2,, 3]"));
        assertEquals(Vectors.of(3L, 3L), eval("[,1 + 2,, 3]"));
    }

}
