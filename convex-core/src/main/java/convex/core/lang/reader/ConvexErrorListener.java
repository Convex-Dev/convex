package convex.core.lang.reader;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.NoViableAltException;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;

import convex.core.exceptions.ParseException;

class ConvexErrorListener extends BaseErrorListener {

	@Override
	public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine,
			String msg, RecognitionException e) {
		if (e instanceof NoViableAltException) {
			NoViableAltException nvae=(NoViableAltException) e;
			Token token = nvae.getStartToken();
			String text=token.getText();
			if ("(".equals(text)) {
				msg="Unmatched '('";
			} else if ("[".equals(text)) {
				msg="Unmatched '['";
			} else if ("{".equals(text)) {
				msg="Unmatched '{'";
			} 
		}
		throw new ParseException("Parse error at "+line+":"+charPositionInLine+" :: "+msg,e);
	}
}
