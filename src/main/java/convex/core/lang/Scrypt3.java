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
public class Scrypt3 extends Reader {

    // Use a ThreadLocal reader because instances are not thread safe
    private static final ThreadLocal<Scrypt3> syntaxReader = ThreadLocal.withInitial(() -> Parboiled.createParser(Scrypt3.class));
    public final Rule DEF = Keyword("def");
    public final Rule COND = Keyword("cond");
    public final Rule FN = Keyword("fn");
    public final Rule IF = Keyword("if");
    public final Rule ELSE = Keyword("else");
    public final Rule WHEN = Keyword("when");
    public final Rule DO = Keyword("do");

    final Rule EQU = Terminal("=", Ch('='));
    final Rule COMMA = Terminal(",");
    final Rule LPAR = Terminal("(");
    final Rule RPAR = Terminal(")");
    final Rule LWING = Terminal("{");
    final Rule RWING = Terminal("}");
    final Rule LBRK = Terminal("[");
    final Rule RBRK = Terminal("]");
    final Rule SEMI = Terminal(";");
    final Rule RIGHT_ARROW = Terminal("->");

    /**
     * Constructor for reader class. Called by Parboiled.createParser
     */
    public Scrypt3() {
        super(true);
    }

    /**
     * Parses an expression and returns a Syntax object
     *
     * @param source
     * @return Parsed form
     */
    @SuppressWarnings("rawtypes")
    public static Syntax readSyntax(String source) {
        Scrypt3 scryptReader = syntaxReader.get();
        scryptReader.tempSource = source;

        var rule = scryptReader.CompilationUnit();
        var result = new ReportingParseRunner(rule).run(source);

        if (result.matched) {
            return (Syntax) result.resultValue;
        } else {
            throw new RuntimeException(rule.toString() + " failed to match " + source);
        }
    }

    // --------------------------------
    // COMPILATION UNIT
    // --------------------------------
    public Rule CompilationUnit() {
        return FirstOf(
                Sequence(
                        Spacing(),
                        FirstOf(
                                Sequence(Expression(), EOI),
                                Sequence(Statement(), EOI),
                                Sequence(StatementList(), push(prepare(popNodeList().cons(Syntax.create(Symbols.DO)))), EOI)
                        )
                ),
                push(error("Invalid program."))
        );
    }

    // --------------------------------
    // STATEMENT
    // --------------------------------
    public Rule Statement() {
        return FirstOf(
                IfElseExpression(),
                WhenStatement(),
                Sequence(DefExpression(), SEMI),
                Sequence(Expression(), SEMI),
                BlockExpression(),
                Sequence(SEMI, push(prepare(null)))
        );
    }

    // --------------------------------
    // EXPRESSION
    // --------------------------------
    public Rule Expression() {
        return Sequence(
                FirstOf(// Special
                        DefExpression(),
                        CondExpression(),
                        DoExpression(),

                        CallableExpression(),
                        FnExpression(),
                        LambdaExpression(),

                        // Scalars
                        StringLiteral(),
                        NilLiteral(),
                        NumberLiteral(),
                        BooleanLiteral(),
                        Symbol(),
                        Keyword(),

                        // Compound
                        VectorExpression(),
                        MapExpression(),
                        Set(),

                        BlockExpression()
                ),
                Spacing()
        );
    }

    // --------------------------------
    // INFIX
    // --------------------------------

    public Rule InfixExpression() {
        return Sequence(
                Expression(),
                InfixOperator(),
                Expression(),
                ZeroOrMore(InfixOperator(), Expression())
        );
    }

    // --------------------------------
    // FUNCTION
    // --------------------------------
    public Rule FnExpression() {
        return Sequence(
                FN,
                ParOptMany(Symbol()),
                Block(),
                push(buildFnExpression())
        );
    }

    public Syntax buildFnExpression() {
        var block = popNodeList();
        var parameters = Vectors.create(popNodeList());

        return Syntax.create(block.cons(parameters).cons(Syntax.create(Symbols.FN)));
    }

    // --------------------------------
    // LAMBDA
    // --------------------------------
    public Rule LambdaExpression() {
        return Sequence(
                // Args []
                ParOptMany(Symbol()),
                // ->
                RIGHT_ARROW,
                // Body
                Expression(),
                push(prepare(lambdaExpression()))
        );
    }

    public AList<Object> lambdaExpression() {
        var fn = Syntax.create(Symbols.FN);
        var body = pop();
        var args = Vectors.create(popNodeList());

        return Lists.of(fn, args, body);
    }

    // --------------------------------
    // BLOCK
    // --------------------------------
    public Rule BlockExpression() {
        return Sequence(Block(), push(prepare(popNodeList().cons(Syntax.create(Symbols.DO)))));
    }

    public Rule Block() {
        return Sequence(
                LWING,
                StatementList(),
                RWING
        );
    }

    public Rule StatementList() {
        Var<ArrayList<Object>> expVar = new Var<>(new ArrayList<>());

        return Sequence(
                ZeroOrMore(
                        Statement(),
                        ListAddAction(expVar)
                ),
                push(prepare(Lists.create(expVar.get())))
        );
    }

    // --------------------------------
    // DO
    // --------------------------------
    public Rule DoExpression() {
        return Sequence(
                DO,
                BlockExpression()
        );
    }

    // --------------------------------
    // COND
    // --------------------------------
    @SuppressWarnings({"unchecked"})
    public Rule CondExpression() {
        return Sequence(
                COND,
                LWING,
                CondTestExpressionList(),
                RWING,
                push(prepare(buildCondExpression((ArrayList<Object>) pop())))

        );
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public AList<Object> buildCondExpression(ArrayList<Object> testExpressionList) {
        var pairs = Lists.create(testExpressionList).flatMap((pair) -> Lists.create((AList) pair));

        return pairs.cons(Syntax.create(Symbols.COND));
    }

    public Rule CondTestExpressionList() {
        var expVar = new Var<>(new ArrayList<>());

        return Sequence(
                CondTestExpressionPair(),
                ListAddAction(expVar),
                ZeroOrMore(
                        COMMA,
                        CondTestExpressionPair(),
                        ListAddAction(expVar)

                ),
                push(expVar.get())
        );
    }

    public Rule CondTestExpressionPair() {
        return Sequence(
                Expression(),
                Expression(),
                push(buildCondTextExpression())
        );
    }

    public ASequence<Object> buildCondTextExpression() {
        var expression = pop();
        var test = pop();

        return Lists.of(test, expression);
    }

    // --------------------------------
    // WHEN
    // --------------------------------
    public Rule WhenStatement() {
        return Sequence(
                WHEN,
                ParExpression(),
                Block(),
                push(prepare(buildWhenExpression()))
        );
    }

    @SuppressWarnings("rawtypes")
    public ASequence buildWhenExpression() {
        // Pop expressions from body
        var body = popNodeList();

        // Pop test
        var test = (Syntax) pop();

        return body.cons(test).cons(Symbols.WHEN);
    }

    // --------------------------------
    // IF ELSE
    // --------------------------------
    // TODO Need separate IfElseStatement?
    public Rule IfElseExpression() {
        return FirstOf(
                Sequence(
                        IF,
                        ParExpression(),
                        Expression(),
                        ELSE,
                        Expression(),
                        push(prepare(buildIfElseExpression()))
                ),
                Sequence(
                        IF,
                        ParExpression(),
                        Expression(),
                        push(prepare(buildIfExpression()))
                )
        );
    }

    @SuppressWarnings("rawtypes")
    public ASequence buildIfExpression() {
        // Pop expressions from if body
        var body = pop();

        // Pop test
        var test = (Syntax) pop();

        return Lists.of(
                Syntax.create(Symbols.IF),
                test,
                body
        );
    }

    @SuppressWarnings("rawtypes")
    public ASequence buildIfElseExpression() {
        // Pop expressions from else body
        var elseBody = pop();

        // Pop expressions from if body
        var ifBody = pop();

        // Pop test
        var test = (Syntax) pop();

        return Lists.of(
                Syntax.create(Symbols.IF),
                test,
                ifBody,
                elseBody
        );
    }


    // --------------------------------
    // DEF
    // --------------------------------
    public Rule DefExpression() {
        return Sequence(
                DEF,
                Symbol(),
                Spacing(),
                EQU,
                Expression(),
                push(prepare(buildDefExpression((Syntax) pop(), (Syntax) pop())))
        );
    }

    public List<Syntax> buildDefExpression(Syntax expr, Syntax sym) {
        return (List<Syntax>) Lists.of(Syntax.create(Symbols.DEF), sym, expr);
    }

    public Rule VectorExpression() {
        return Sequence(
                LBRK,
                OptManyCommaSeparated(Expression()),
                FirstOf(
                        RBRK,
                        Sequence(
                                FirstOf(RWING, RPAR, EOI),
                                push(error("Expected closing ']'"))
                        )
                ),
                Spacing(),
                push(prepare(Vectors.create(popNodeList()))));
    }

    public Rule MapExpression() {
        return Sequence(
                LWING,
                MapEntries(),
                RWING,
                Spacing(),
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

    public Rule Callable() {
        return FirstOf(
                FnExpression(),
                VectorExpression(),
                MapExpression(),
                Set()
        );
    }

    public Rule CallableExpression() {
        return Sequence(
                FirstOf(
                        Callable(),
                        Symbol()
                ),
                Spacing(),
                ParOptMany(Expression()),
                push(prepare(callableExpression()))
        );
    }

    public ASequence<Object> callableExpression() {
        var args = popNodeList();
        var callableOrSym = pop();

        return Lists.create(args).cons(callableOrSym);
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

    public Rule CompoundExpression() {
        return FirstOf(
                CallableExpression(),
                Sequence(
                        Expression(),
                        ZeroOrMore(Sequence(Spacing(), InfixExtension()))
                )
        );
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
        return Sequence(string, Spacing()).label('\'' + string + '\'');
    }

    @SuppressNode
    @DontLabel
    Rule Terminal(String string, Rule mustNotFollow) {
        return Sequence(string, TestNot(mustNotFollow), Spacing()).label('\'' + string + '\'');
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

    public Rule ParExpression() {
        return Sequence(LPAR, Expression(), RPAR);
    }

    public Rule ParOptMany(Rule rule) {
        return Sequence(
                LPAR,
                OptManyCommaSeparated(rule),
                RPAR
        );
    }

    public Rule OptManyCommaSeparated(Rule rule) {
        Var<ArrayList<Object>> expVar = new Var<>(new ArrayList<>());

        return Sequence(
                Optional(
                        rule,
                        ListAddAction(expVar),
                        ZeroOrMore(
                                COMMA,
                                rule,
                                ListAddAction(expVar)
                        )
                ),
                push(prepare(Lists.create(expVar.get())))
        );
    }

}
