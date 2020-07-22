package convex.core.lang;

import java.util.ArrayList;

import org.parboiled.Parboiled;
import org.parboiled.Rule;
import org.parboiled.annotations.BuildParseTree;
import org.parboiled.parserunners.ReportingParseRunner;
import org.parboiled.support.Var;

import convex.core.data.List;
import convex.core.data.Lists;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.data.Vectors;

@BuildParseTree
public class Scrypt extends Reader {

    public Rule ExpressionInput() {
        return FirstOf(Sequence(
                Spacing(),
                CompoundExpression(),
                Spacing(),
                EOI),
                push(error("Single expression expected")));
    }

    public Rule Vector() {
        return Sequence(
                '[',
                CompoundExpressionList(),
                FirstOf(']', Sequence(FirstOf(AnyOf("})"), EOI), push(error("Expected closing ']'")))),
                push(prepare(Vectors.create(popNodeList()))));
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
                Vector());
    }

    public Rule NestedExpression() {
        return Sequence("(", Spacing(), CompoundExpression(), Spacing(), ")");
    }

    public Rule FunctionApplication() {
        return Sequence(
                Expression(),
                Spacing(),
                FunctionParameters());
    }

    public Rule FunctionParameters() {
        return Sequence("(", Spacing(), Optional(FunctionParametersDecls()), Spacing(), ")");
    }

    public Rule FunctionParametersDecls() {
        return Sequence(CompoundExpression(), Optional(",", Spacing(), FunctionParametersDecls()));
    }

    public Rule InfixOperator() {
        return FirstOf(
                Sequence("+", push(Symbols.PLUS)),
                Sequence("-", push(Symbols.MINUS)),
                Sequence("*", push(Symbols.TIMES)),
                Sequence("/", push(Symbols.DIVIDE)),
                Sequence("==", push(Symbols.EQUALS)));
    }

    public Rule InfixExpression() {
        return Sequence(
                Spacing(),
                InfixOperator(),
                Spacing(),
                Expression(),
                push(prepare(createInfixForm((Syntax) pop(), (Symbol) pop(), (Syntax) pop()))));
    }

    public List<Syntax> createInfixForm(Syntax op1, Symbol symbol, Syntax op2) {
        return List.of(Syntax.create(symbol), op1, op2);
    }

    public Rule CompoundExpression() {
        return FirstOf(
                Sequence(
                        Expression(),
                        ZeroOrMore(Sequence(Spacing(), InfixExpression()))),
                FunctionApplication());
    }

    public Rule CompoundExpressionList() {
        Var<ArrayList<Object>> expVar = new Var<>(new ArrayList<>());
        return Sequence(
                Spacing(),
                ZeroOrMore(Sequence( // initial expressions with following whitespace or delimiter
                        CompoundExpression(),
                        Spacing(),
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
        return (Syntax) doParse(new ReportingParseRunner<>(scryptReader.ExpressionInput()), source);
    }

}
