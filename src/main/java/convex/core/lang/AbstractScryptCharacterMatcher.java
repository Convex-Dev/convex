package convex.core.lang;

import org.parboiled.MatcherContext;
import org.parboiled.matchers.CustomMatcher;

public abstract class AbstractScryptCharacterMatcher extends CustomMatcher {

    protected AbstractScryptCharacterMatcher(String label) {
        super(label);
    }

    @Override
    public final boolean isSingleCharMatcher() {
        return true;
    }

    @Override
    public final boolean canMatchEmpty() {
        return false;
    }

    @Override
    public boolean isStarterChar(char c) {
        return acceptChar(c);
    }

    @Override
    public final char getStarterChar() {
        return 'a';
    }

    public final <V> boolean match(MatcherContext<V> context) {
        if (!acceptChar(context.getCurrentChar())) {
            return false;
        }
        context.advanceIndex(1);
        context.createNode();
        return true;
    }

    protected abstract boolean acceptChar(char c);
}