package convex.core.lang;

import java.util.ArrayList;

import org.parboiled.Parboiled;
import org.parboiled.Rule;
import org.parboiled.annotations.BuildParseTree;
import org.parboiled.annotations.DontLabel;
import org.parboiled.annotations.SuppressNode;
import org.parboiled.parserunners.ReportingParseRunner;
import org.parboiled.support.Var;

import convex.core.data.AList;
import convex.core.data.List;
import convex.core.data.Lists;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.data.Vectors;

@BuildParseTree
public class Scrypt extends Reader {

    public Rule Spacing() {
        return ZeroOrMore(FirstOf(

                // whitespace
                OneOrMore(AnyOf(" \t\r\n\f").label("Whitespace")),

                // traditional comment
                Sequence("/*", ZeroOrMore(TestNot("*/"), ANY), "*/"),

                // end of line comment
                Sequence(
                        "//",
                        ZeroOrMore(TestNot(AnyOf("\r\n")), ANY),
                        FirstOf("\r\n", '\r', '\n', EOI)
                )
        ));
    }

    Rule Arguments() {
        Var<ArrayList<Object>> expVar = new Var<>(new ArrayList<>());

        return Sequence(
                LPAR,
                Optional(Argument(expVar), ZeroOrMore(COMMA, Argument(expVar))),
                RPAR,
                push(prepare(Lists.create(expVar.get())))
        );
    }

    Rule Argument(Var<ArrayList<Object>> expVar) {
        return Sequence(
                CompoundExpression(),
                ListAddAction(expVar)
        );
    }

    public Rule CompilationUnit() {
        return FirstOf(Sequence(
                Spacing(),
                FirstOf(Statement(), CompoundExpression()),
                Spacing(),
                EOI),
                push(error("Single expression expected")));
    }

    public Rule Vector() {
        return Sequence(
                '[',
                Spacing(),
                CompoundExpressionList(),
                Spacing(),
                FirstOf(']', Sequence(FirstOf(AnyOf("})"), EOI), push(error("Expected closing ']'")))),
                push(prepare(Vectors.create(popNodeList()))));
    }

    public Rule ExpressionStatement() {
        return Sequence(CompoundExpression(), SEMI);
    }

    public Rule Statement() {
        return FirstOf(
                DefStatement(),
                LocalSetStatement(),
                ExpressionStatement()
        );
    }

    public Rule DefStatement() {
        return Sequence(
                Spacing(),
                "def",
                Spacing(),
                Symbol(),
                EQU,
                Expression(),
                SEMI,
                push(prepare(buildDefStatement((Syntax) pop(), (Syntax) pop())))
        );
    }

    public List<Syntax> buildDefStatement(Syntax expr, Syntax sym) {
        return (List<Syntax>) Lists.of(Syntax.create(Symbols.DEF), sym, expr);
    }

    public Rule LocalSetStatement() {
        return Sequence(
                Spacing(),
                Symbol(),
                EQU,
                Expression(),
                SEMI,
                push(prepare(buildLocalSetStatement((Syntax) pop(), (Syntax) pop())))
        );
    }

    public List<Syntax> buildLocalSetStatement(Syntax expr, Syntax sym) {
        return (List<Syntax>) Lists.of(Syntax.create(Symbols.SET_BANG), sym, expr);
    }

    /**
     * One or more expressions wrapped in '{ }' separated by ';'.
     * <p>
     * Compiles to '(do expression+ )'.
     *
     * @return Rule
     */
    public Rule BlockExpression() {
        return Sequence(
                LWING,
                BlockBody(),
                RWING,
                push(prepare(Lists.create(popNodeList()).cons(Symbols.DO)))
        );
    }

    public Rule BlockBody() {
        Var<ArrayList<Object>> expVar = new Var<>(new ArrayList<>());

        return Sequence(
                OneOrMore(
                        Sequence(
                                Statement(),
                                ListAddAction(expVar)
                        )
                ),
                push(prepare(Lists.create(expVar.get()))));
    }

    public Rule MapLiteralExpression() {
        return Sequence(
                LWING,
                MapEntries(),
                RWING,
                // Create a Map from a List of MapEntry.
                // `MapEntries` builds up a list of MapEntry,
                // which we can get from `popNodeList`.
                push(prepare(Maps.create(popNodeList())))
        );
    }

    public Rule MapEntries() {
        Var<ArrayList<Object>> expVar = new Var<>(new ArrayList<>());

        return Sequence(
                Optional(
                        MapEntry(),
                        ListAddAction(expVar),
                        ZeroOrMore(
                                COMMA,
                                MapEntry(),
                                ListAddAction(expVar))),
                push(prepare(Lists.create(expVar.get()))));
    }

    public Rule MapEntry() {
        return Sequence(
                Expression(),
                Spacing(),
                Expression(),
                push(buildMapEntry((Syntax) pop(), (Syntax) pop()))
        );
    }

    public MapEntry<Syntax, Syntax> buildMapEntry(Syntax v, Syntax k) {
        return MapEntry.create(k, v);
    }

    public Rule Expression() {
        return FirstOf(
                NestedExpression(),
                NilLiteral(),
                NumberLiteral(),
                StringLiteral(),
                BooleanLiteral(),
                Keyword(),
                Symbol(),
                Vector(),
                MapLiteralExpression(),
                BlockExpression());
    }

    public Rule NestedExpression() {
        return Sequence("(", Spacing(), CompoundExpression(), Spacing(), ")");
    }

    @SuppressWarnings("unchecked")
	public Rule FunctionApplication() {
        return Sequence(
                Expression(),
                Spacing(),
                Arguments(),
                push(prepare(((AList<Syntax>) ((Syntax) pop()).getValue()).cons((Syntax)pop()))));
    }

    public Rule InfixOperator() {
        return FirstOf(
                Sequence("+", push(Symbols.PLUS)),
                Sequence("-", push(Symbols.MINUS)),
                Sequence("*", push(Symbols.TIMES)),
                Sequence("/", push(Symbols.DIVIDE)),
                Sequence("==", push(Symbols.EQUALS)));
    }

    public Rule InfixExtension() {
        return Sequence(
                Spacing(),
                InfixOperator(),
                Spacing(),
                CompoundExpression(),
                push(prepare(createInfixForm((Syntax) pop(), (Symbol) pop(), (Syntax) pop()))));
    }

    public List<Syntax> createInfixForm(Syntax op1, Symbol symbol, Syntax op2) {
        return List.of(Syntax.create(symbol), op2, op1);
    }

    public Rule CompoundExpression() {
        return FirstOf(
                FunctionApplication(),
                Sequence(
                        Expression(),
                        ZeroOrMore(Sequence(Spacing(), InfixExtension()))
                )
        );
    }


    public Rule CompoundExpressionList() {
        Var<ArrayList<Object>> expVar = new Var<>(new ArrayList<>());
        return Sequence(
                Spacing(),
                ZeroOrMore(
                        Sequence( // initial expressions with following whitespace or delimiter
                                CompoundExpression(),
                                COMMA,
                                ListAddAction(expVar))),
                Optional(
                        Sequence( // final expression without whitespace
                                CompoundExpression(), ListAddAction(expVar))),
                push(prepare(Lists.create(expVar.get()))));
    }


    /**
     * Constructor for reader class. Called by Parboiled.createParser
     */
    public Scrypt() {
        super(true);
    }

    // Use a ThreadLocal reader because instances are not thread safe
    private static final ThreadLocal<Scrypt> syntaxReader = ThreadLocal.withInitial(() -> Parboiled.createParser(Scrypt.class));

    /**
     * Parses an expression and returns a Syntax object
     *
     * @param source
     * @return Parsed form
     */
    public static Syntax readSyntax(String source) {
        Scrypt scryptReader = syntaxReader.get();
        scryptReader.tempSource = source;
        return doParse(new ReportingParseRunner<>(scryptReader.CompilationUnit()), source);
    }


    final Rule EQU = Terminal("=", Ch('='));
    final Rule COMMA = Terminal(",");
    final Rule LPAR = Terminal("(");
    final Rule RPAR = Terminal(")");
    final Rule LWING = Terminal("{");
    final Rule RWING = Terminal("}");
    final Rule SEMI = Terminal(";");

    @SuppressNode
    @DontLabel
    Rule Terminal(String string) {
        return Sequence(Spacing(), string, Spacing()).label('\'' + string + '\'');
    }

    @SuppressNode
    @DontLabel
    Rule Terminal(String string, Rule mustNotFollow) {
        return Sequence(Spacing(), string, TestNot(mustNotFollow), Spacing()).label('\'' + string + '\'');
    }

}
