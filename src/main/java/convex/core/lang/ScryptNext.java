package convex.core.lang;

import java.util.ArrayList;

import org.parboiled.Parboiled;
import org.parboiled.Rule;
import org.parboiled.annotations.BuildParseTree;
import org.parboiled.annotations.DontLabel;
import org.parboiled.annotations.MemoMismatches;
import org.parboiled.annotations.SuppressNode;
import org.parboiled.parserunners.ReportingParseRunner;
import org.parboiled.support.Var;

import convex.core.data.AList;
import convex.core.data.ASequence;
import convex.core.data.AVector;
import convex.core.data.List;
import convex.core.data.Lists;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.data.Vectors;

@BuildParseTree
public class ScryptNext extends Reader {

    // Use a ThreadLocal reader because instances are not thread safe
    private static final ThreadLocal<ScryptNext> syntaxReader = ThreadLocal.withInitial(() -> Parboiled.createParser(ScryptNext.class));
    public final Rule DEF = Keyword("def");
    public final Rule DEFN = Keyword("defn");
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
    public ScryptNext() {
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
        ScryptNext scryptReader = syntaxReader.get();
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
                                Sequence(
                                        ZeroOrMoreOf(Statement()),
                                        push(prepare(popNodeList().cons(Syntax.create(Symbols.DO)))), EOI
                                )
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
                IfElseStatement(),
                WhenStatement(),
                DefStatement(),
                DefnStatement(),
                BlockStatement(),
                EmptyStatement(),
                ExpressionStatement()
        );
    }

    // --------------------------------
    // IF ELSE STATEMENT
    // --------------------------------
    public Rule IfElseStatement() {
        return FirstOf(
                Sequence(
                        IF,
                        ParExpression(),
                        Statement(),
                        ELSE,
                        Statement(),
                        push(prepare(ifElseStatement()))
                ),
                Sequence(
                        IF,
                        ParExpression(),
                        Statement(),
                        push(prepare(ifStatement()))
                )
        );
    }

    @SuppressWarnings("rawtypes")
    public ASequence ifStatement() {
        // Pop expressions from if body
        var body = pop();

        // Pop test
        var test = pop();

        return Lists.of(
                Syntax.create(Symbols.COND),
                test,
                body
        );
    }

    @SuppressWarnings("rawtypes")
    public ASequence ifElseStatement() {
        // Pop expressions from else body
        var elseBody = pop();

        // Pop expressions from if body
        var ifBody = pop();

        // Pop test
        var test = (Syntax) pop();

        return Lists.of(
                Syntax.create(Symbols.COND),
                test,
                ifBody,
                elseBody
        );
    }

    // --------------------------------
    // WHEN STATEMENT
    // --------------------------------
    public Rule WhenStatement() {
        return Sequence(
                WHEN,
                ParExpression(),
                Statement(),
                push(prepare(whenStatement()))
        );
    }

    public AList<Syntax> whenStatement() {
        // Pop expressions from body
        Syntax body = (Syntax) pop();

        // Pop test
        Syntax test = (Syntax) pop();

        return Lists.of(
                Syntax.create(Symbols.COND),
                test,
                body
        );
    }

    // --------------------------------
    // DEF STATEMENT
    // --------------------------------
    public Rule DefStatement() {
        return Sequence(
                DEF,
                Symbol(),
                Spacing(),
                EQU,
                Expression(),
                SEMI,
                push(prepare(defStatement((Syntax) pop(), (Syntax) pop())))
        );
    }

    public List<Syntax> defStatement(Syntax expr, Syntax sym) {
        return (List<Syntax>) Lists.of(Syntax.create(Symbols.DEF), sym, expr);
    }

    // --------------------------------
    // DEFN STATEMENT
    // --------------------------------
    public Rule DefnStatement() {
        return Sequence(
                DEFN,
                Symbol(),
                WrapInParenthesis(ZeroOrMoreCommaSeparatedOf(Symbol())),
                WrapInCurlyBraces(ZeroOrMoreOf(Statement())),
                push(prepare(defnStatement()))
        );
    }

    public AList<Object> defnStatement() {
        var block = popNodeList();
        var parameters = Vectors.create(popNodeList());
        var name = pop();

        return Lists.of(
                Syntax.create(Symbols.DEF),
                name,
                Syntax.create(block
                        .cons(parameters)
                        .cons(Syntax.create(Symbols.FN))
                )
        );
    }

    // --------------------------------
    // BLOCK STATEMENT
    // --------------------------------
    public Rule BlockStatement() {
        return Sequence(
                WrapInCurlyBraces(ZeroOrMoreOf(Statement())),
                TestNot(SEMI),
                push(block(popNodeList()))
        );
    }

    public Object block(ASequence<Object> statements) {
        Object form;

        switch (statements.size()) {
            case 0:
                form = null;
                break;
            case 1:
                form = statements.get(0);
                break;
            default:
                form = statements.cons(Syntax.create(Symbols.DO));
        }

        return prepare(form);
    }

    // --------------------------------
    // EXPRESSION STATEMENT
    // --------------------------------
    public Rule ExpressionStatement() {
        return Sequence(Expression(), SEMI);
    }

    // --------------------------------
    // EMPTY STATEMENT
    // --------------------------------
    public Rule EmptyStatement() {
        return Sequence(SEMI, push(prepare(null)));
    }


    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~


    public Rule Literals() {
        return FirstOf(
                StringLiteral(),
                NilLiteral(),
                NumberLiteral(),
                BooleanLiteral(),
                Keyword()
        );
    }

    // --------------------------------
    // EXPRESSION
    // --------------------------------
    public Rule Expression() {
        return Sequence(
                ExpressionPrecedence(),
                Spacing()
        );
    }

    public Rule ExpressionPrecedence() {
        return Arithmetic1Expression();
    }

    public Rule Arithmetic1Expression() {
        Var<ArrayList<Object>> expVar = new Var<>(new ArrayList<>());

        return Sequence(
                Arithmetic2Expression(),
                ListAddAction(expVar),
                Spacing(),
                ZeroOrMore(Arithmetic1(), ListAddAction(expVar)),
                push(prepare(arithmeticExpression(expVar.get())))
        );
    }

    public Rule Arithmetic1() {
        return Sequence(
                FirstOf(
                        Sequence("+", push(prepare(Symbols.PLUS))),
                        Sequence("-", push(prepare(Symbols.MINUS)))
                ),
                Spacing(),
                Arithmetic2Expression(),
                Spacing(),
                push(arithmetic())
        );
    }

    public Rule Arithmetic2Expression() {
        Var<ArrayList<Object>> expVar = new Var<>(new ArrayList<>());

        return Sequence(
                Primary(),
                ListAddAction(expVar),
                Spacing(),
                ZeroOrMore(Arithmetic2(), ListAddAction(expVar)),
                push(prepare(arithmeticExpression(expVar.get())))
        );
    }

    public Rule Arithmetic2() {
        return Sequence(
                FirstOf(
                        Sequence("*", push(prepare(Symbols.TIMES))),
                        Sequence("/", push(prepare(Symbols.DIVIDE)))
                ),
                Spacing(),
                Primary(),
                Spacing(),
                push(arithmetic())
        );
    }

    @SuppressWarnings("unchecked")
	public Syntax arithmeticExpression(ArrayList<Object> exprs) {
        var primary = (Syntax) exprs.get(0);

        if (exprs.size() == 1) {
            return primary;
        } else {
            // List of Tuple2<Syntax, Syntax> - see arithmetic()
            var rest = exprs.subList(1, exprs.size());

            var rest1 = (AVector<Syntax>) rest.remove(0);

            var sexp = Lists.of(rest1.get(0), primary, rest1.get(1));

            for (Object o : rest) {
                var operatorAndOperand = (AVector<Syntax>) o;

                sexp = Lists.of(operatorAndOperand.get(0), Syntax.create(sexp), operatorAndOperand.get(1));
            }

            return Syntax.create(sexp);
        }
    }

    public AVector<Syntax> arithmetic() {
        var operand = (Syntax) pop();
        var operator = (Syntax) pop();

        return Vectors.of(operator, operand);
    }

    public Rule Primary() {
        return FirstOf(
                CallableExpression(),
                DoExpression(),
                FnExpression(),
                LambdaExpression(),
                ParExpression(),
                Literals(),
                Symbol(),
                Vector(),
                Map(),
                Set()
        );
    }

    // --------------------------------
    // DO EXPRESSION
    // --------------------------------
    public Rule DoExpression() {
        return Sequence(
                DO,
                WrapInCurlyBraces(ZeroOrMoreOf(Statement())),
                push(block(popNodeList()))
        );
    }

    // --------------------------------
    // CALLABLE EXPRESSION
    // --------------------------------
    public Rule Callable() {
        return FirstOf(
                FnExpression(),
                Vector(),
                Map(),
                Set()
        );
    }

    public Rule CallableExpression() {
        Var<ArrayList<Object>> expVar = new Var<>(new ArrayList<>());

        return Sequence(
                FirstOf(
                        Callable(),
                        Symbol()
                ),
                Spacing(),
                WrapInParenthesis(ZeroOrMoreCommaSeparatedOf(Expression())),
                ZeroOrMore(WrapInParenthesis(ZeroOrMoreCommaSeparatedOf(Expression())), ListAddAction(expVar)),
                push(prepare(callableExpression(expVar.get())))
        );
    }

    public AList<Object> callableExpression(ArrayList<Object> listOfPar) {
        var args = popNodeList();
        var callableOrSym = pop();

        if (listOfPar.isEmpty()) {
            return Lists.create(args).cons(callableOrSym);
        } else {
            AList<Object> acc = Lists.create(args).cons(callableOrSym);

            for (Object o : listOfPar) {
                AList<Object> l = ((Syntax) o).getValue();

                acc = l.cons(acc);
            }
            return acc;
        }
    }

    // --------------------------------
    // FUNCTION EXPRESSION
    // --------------------------------
    public Rule FnExpression() {
        return Sequence(
                FN,
                WrapInParenthesis(ZeroOrMoreCommaSeparatedOf(Symbol())),
                WrapInCurlyBraces(ZeroOrMoreOf(Statement())),
                push(fnExpression())
        );
    }

    public Syntax fnExpression() {
        var block = popNodeList();
        var parameters = Vectors.create(popNodeList());

        return Syntax.create(block.cons(parameters).cons(Syntax.create(Symbols.FN)));
    }

    // --------------------------------
    // LAMBDA EXPRESSION
    // --------------------------------
    public Rule LambdaExpression() {
        return Sequence(
                WrapInParenthesis(ZeroOrMoreCommaSeparatedOf(Symbol())),
                RIGHT_ARROW,
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
    // INFIX
    // --------------------------------
    public Rule InfixExtension() {
        return Sequence(
                InfixOperator(),
                Expression(),
                push(prepare(infixExpression()))
        );
    }

    public AList<Object> infixExpression() {
        var operand2 = pop();
        var operator = pop();
        var operand1 = pop();

        return Lists.of(operator, operand1, operand2);
    }

    public Rule Vector() {
        return Sequence(
                LBRK,
                ZeroOrMoreCommaSeparatedOf(Expression()),
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

    public Rule Map() {
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

    public Rule InfixOperator() {
        return Sequence(
                FirstOf(
                        Sequence("+", push(prepare(Symbols.PLUS))),
                        Sequence("-", push(prepare(Symbols.MINUS))),
                        Sequence("*", push(prepare(Symbols.TIMES))),
                        Sequence("/", push(prepare(Symbols.DIVIDE))),
                        Sequence("==", push(prepare(Symbols.EQUALS))),
                        Sequence("<=", push(prepare(Symbols.LE))),
                        Sequence("<", push(prepare(Symbols.LT))),
                        Sequence(">=", push(prepare(Symbols.GE))),
                        Sequence(">", push(prepare(Symbols.GT)))
                ),
                Spacing()
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

    public Rule InitialSymbolCharacter() {
        return Alphabet();
    }

    public Rule FollowingSymbolCharacter() {
        return FirstOf(AlphaNumeric(), AnyOf("_?!"));
    }

    public Rule Symbol() {
        return FirstOf(
                Sequence(
                        Sequence(InitialSymbolCharacter(), ZeroOrMore(FollowingSymbolCharacter())),
                        // '_' must be replaced by '-'
                        // Dash '-' is not a valid Scrypt symbol, an underscore '_'
                        // is used instead but it must be converted to '-'.
                        // TODO
                        // _address_ => *address*
                        // _address *address
                        // address_ address*
                        push(prepare(convexLispSymbol(match())))
                ),
                // Infix operators can be passed as arguments to high oder functions e.g.: reduce(+, 0, [1,2,3])
                InfixOperator()
        );
    }

    public Symbol convexLispSymbol(String s) {
        var symStr = s.replaceAll("(^_|_$)", "*").replaceAll("_", "-");

        return Symbol.create(symStr);
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
        return WrapInParenthesis(Expression());
    }

    public Rule WrapInParenthesis(Rule rule) {
        return Sequence(
                LPAR,
                rule,
                RPAR
        );
    }

    public Rule WrapInCurlyBraces(Rule rule) {
        return Sequence(
                LWING,
                rule,
                RWING
        );
    }

    public Rule ZeroOrMoreOf(Rule rule) {
        Var<ArrayList<Object>> expVar = new Var<>(new ArrayList<>());

        return Sequence(
                ZeroOrMore(
                        rule,
                        ListAddAction(expVar)
                ),
                push(prepare(Lists.create(expVar.get())))
        );
    }

    public Rule ZeroOrMoreCommaSeparatedOf(Rule rule) {
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
