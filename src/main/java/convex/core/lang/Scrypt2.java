package convex.core.lang;

import convex.core.data.*;
import org.parboiled.Parboiled;
import org.parboiled.Rule;
import org.parboiled.annotations.BuildParseTree;
import org.parboiled.annotations.DontLabel;
import org.parboiled.annotations.MemoMismatches;
import org.parboiled.annotations.SuppressNode;
import org.parboiled.parserunners.ReportingParseRunner;
import org.parboiled.support.Var;

import java.util.ArrayList;

@BuildParseTree
public class Scrypt2 extends Reader {

    // Use a ThreadLocal reader because instances are not thread safe
    private static final ThreadLocal<Scrypt2> syntaxReader = ThreadLocal.withInitial(() -> Parboiled.createParser(Scrypt2.class));
    public final Rule IF = Keyword("if");
    public final Rule ELSE = Keyword("else");
    final Rule EQU = Terminal("=", Ch('='));
    final Rule COMMA = Terminal(",");
    final Rule LPAR = Terminal("(");
    final Rule RPAR = Terminal(")");
    final Rule LWING = Terminal("{");
    final Rule RWING = Terminal("}");
    final Rule SEMI = Terminal(";");

    /**
     * Constructor for reader class. Called by Parboiled.createParser
     */
    public Scrypt2() {
        super(true);
    }

    /**
     * Parses an expression and returns a Syntax object
     *
     * @param source
     * @return Parsed form
     */
    public static Syntax readSyntax(String source) {
        Scrypt2 scryptReader = syntaxReader.get();
        scryptReader.tempSource = source;
        return doParse(new ReportingParseRunner<>(scryptReader.CompilationUnit()), source);
    }

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

    Rule Argument(Var<ArrayList<Object>> expVar) {
        return Sequence(
                CompoundExpression(),
                ListAddAction(expVar)
        );
    }

    public Rule CompilationUnit() {
        return FirstOf(
                Sequence(
                        Spacing(),
                        Expression(),
                        Spacing(),
                        EOI
                ),
                push(error("Invalid program."))
        );
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

    public Rule IfElseStatement() {
        return Sequence(
                IF,
                IfTestExpression(),
                Spacing(),
                CompoundExpression(),
                Spacing(),
                Optional(
                        ELSE,
                        Statement()
                ),
                push(prepare(buildIfElseStatement((Syntax) pop(), (Syntax) pop())))
        );
    }

    public List<Syntax> buildIfElseStatement(Syntax thenExpression, Syntax testExpression) {
        return (List<Syntax>) Lists.of(Syntax.create(Symbols.COND), testExpression, thenExpression);
    }

    public Rule Statement() {
        return FirstOf(
                IfElseStatement(),
                DefExpression(),
                LocalSetStatement(),
                ExpressionStatement()
        );
    }

    public Rule DefExpression() {
        return Sequence(
                Spacing(),
                "def",
                Spacing(),
                Symbol(),
                EQU,
                Expression(),
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
     * One or more expressions wrapped in 'do { }' separated by ';'.
     * <p>
     * Compiles to '(do expression+ )'.
     *
     * @return Rule
     */
    public Rule DoExpression() {
        return Sequence(
                "do",
                Spacing(),
                LWING,
                DoBody(),
                RWING,
                push(prepare(Lists.create(popNodeList()).cons(Symbols.DO)))
        );
    }

    public Rule DoBody() {
        Var<ArrayList<Object>> expVar = new Var<>(new ArrayList<>());

        return Sequence(
                ZeroOrMore(
                        Spacing(),
                        Expression(),
                        Spacing(),
                        ListAddAction(expVar)
                ),
                push(prepare(Lists.create(expVar.get())))
        );
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
                FunctionApplication(),
                DoExpression(),
                DefExpression(),

                // Scalars
                NumberLiteral(),
                BooleanLiteral(),
                Symbol(),
                Keyword(),

                // Compound
                Vector(),
                MapLiteralExpression()
        );
    }

    public Rule NestedExpression() {
        return Sequence("(", Spacing(), CompoundExpression(), Spacing(), ")");
    }

    public Rule IfTestExpression() {
        return CompoundExpression();
    }

    @SuppressWarnings("unchecked")
    public Rule FunctionApplication() {
        return Sequence(
                Symbol(),
                Spacing(),
                FunctionApplicationArguments(),
                push(prepare(((AList<Syntax>) ((Syntax) pop()).getValue()).cons((Syntax) pop())))
        );
    }

    Rule FunctionApplicationArguments() {
        Var<ArrayList<Object>> expVar = new Var<>(new ArrayList<>());

        return Sequence(
                LPAR,
                Optional(Argument(expVar), ZeroOrMore(COMMA, Argument(expVar))),
                RPAR,
                push(prepare(Lists.create(expVar.get())))
        );
    }

    public Rule InfixOperator() {
        return FirstOf(
                Sequence("+", push(Symbols.PLUS)),
                Sequence("-", push(Symbols.MINUS)),
                Sequence("*", push(Symbols.TIMES)),
                Sequence("/", push(Symbols.DIVIDE)),
                Sequence("==", push(Symbols.EQUALS)),
                Sequence("<=", push(Symbols.LE)),
                Sequence("<", push(Symbols.LT)),
                Sequence(">=", push(Symbols.GE)),
                Sequence(">", push(Symbols.GT))
        );
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

    public Rule BinaryExpression() {
        return Sequence(
                Expression(),
                Spacing(),
                InfixOperator(),
                Spacing(),
                Expression(),
                push(prepare(buildBinaryExpression((Syntax) pop(), (Symbol) pop(), (Syntax) pop())))
        );
    }

    public List<Syntax> buildBinaryExpression(Syntax rhs, Symbol operator, Syntax lhs) {
        return (List<Syntax>) Lists.of(Syntax.create(operator), lhs, rhs);
    }

    public Rule CompoundExpression() {
        return FirstOf(
                BinaryExpression(),
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

    public Rule HexDigit() {
        return FirstOf(CharRange('a', 'f'), CharRange('A', 'F'), CharRange('0', '9'));
    }

    Rule UnicodeEscape() {
        return Sequence(OneOrMore('u'), HexDigit(), HexDigit(), HexDigit(), HexDigit());
    }

    @MemoMismatches
    Rule LetterOrDigit() {
        return FirstOf(Sequence('\\', UnicodeEscape()), new ScryptLetterOrDigitMatcher());
    }

    @SuppressNode
    @DontLabel
    Rule Keyword(String keyword) {
        return Terminal(keyword, LetterOrDigit());
    }

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
