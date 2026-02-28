package convex.core.lang.reader;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.NoViableAltException;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;

import convex.core.exceptions.ParseException;

public class ConvexErrorListener extends BaseErrorListener {

	@Override
	public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine,
			String msg, RecognitionException e) {
		if (e instanceof NoViableAltException nvae) {
			Token startToken = nvae.getStartToken();
			String startText=startToken.getText();
			boolean isUnclosed="(".equals(startText)||"[".equals(startText)||"{".equals(startText);
			if (isUnclosed) {
				// Check if the offending token is EOF — if so, emphasise the truncation
				if (offendingSymbol instanceof Token tok && "<EOF>".equals(tok.getText())) {
					msg="unexpected end of input (unclosed '"+startText+"' at "+startToken.getLine()+":"+startToken.getCharPositionInLine()+")";
				} else {
					msg="Unmatched '"+startText+"'";
				}
			}
		} else if (offendingSymbol instanceof Token tok) {
			// Parser error: produce human-readable messages instead of ANTLR jargon
			String text=tok.getText();
			if ("<EOF>".equals(text)) {
				msg="unexpected end of input";
			} else {
				msg="unexpected '"+text+"'";
			}
		} else {
			// Lexer error: offendingSymbol is null, rewrite common patterns
			msg=describeLexerError(msg);
		}
		throw new ParseException("Parse error at "+line+":"+charPositionInLine+": "+msg,e);
	}

	private static String describeLexerError(String msg) {
		// ANTLR lexer errors have the form "token recognition error at: 'X'"
		String prefix="token recognition error at: '";
		if (msg!=null && msg.startsWith(prefix) && msg.endsWith("'")) {
			String text=msg.substring(prefix.length(), msg.length()-1);
			if (text.startsWith("\"")) return "unterminated string";
			if (text.startsWith("#")) return "invalid '#' sequence: '"+text+"'";
			return "unexpected '"+text+"'";
		}
		return msg;
	}
}
