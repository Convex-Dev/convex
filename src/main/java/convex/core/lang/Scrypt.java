package convex.core.lang;

import convex.core.data.*;
import org.parboiled.Parboiled;
import org.parboiled.Rule;
import org.parboiled.annotations.BuildParseTree;
import org.parboiled.parserunners.ReportingParseRunner;
import org.parboiled.support.Var;

import java.util.ArrayList;

@BuildParseTree
public class Scrypt extends Reader {

    public Rule Vector() {
        return Sequence(
                '[',
                CompoundExpressionList(),
                FirstOf(']', Sequence(FirstOf(AnyOf("})"), EOI), push(error("Expected closing ']'")))),
                push(prepare(Vectors.create(popNodeList()))));
    }

    public Rule ExpressionInput() {
        return FirstOf(Sequence(
                Spacing(),
                CompoundExpression(),
                Spacing(),
                EOI),
                push(error("Single expression expected")));
    }

    public Rule ScryptLiteral() {
        return FirstOf(NumberLiteral(), StringLiteral(), NilLiteral(), BooleanLiteral(), Keyword());
    }

    public Rule Expression() {
        return FirstOf(
                NilLiteral(),
                NumberLiteral(),
                StringLiteral(),
                BooleanLiteral(),
                Keyword(),
                Symbol(),
                Vector());
    }

    public Rule InfixExpression() {
        return Sequence(
                '+',
                Spacing(),
                Expression(),
                push(Syntax.create(List.of(Syntax.create(Symbols.PLUS), pop(), pop()))));
    }

    /**
     * Matches a single expression without whitespace
     * <p>
     * Returns the expression value at top of stack.
     */
    public Rule CompoundExpression() {
        return Sequence(
                Expression(),
                ZeroOrMore(Sequence(Spacing(), InfixExpression())));
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
     *
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
