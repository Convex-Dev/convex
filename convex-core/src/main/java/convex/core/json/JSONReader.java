package convex.core.json;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.NoSuchElementException;

import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.misc.ParseCancellationException;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Cells;
import convex.core.data.Maps;
import convex.core.data.Vectors;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMDouble;
import convex.core.exceptions.ParseException;
import convex.core.json.reader.antlr.JSONBaseListener;
import convex.core.json.reader.antlr.JSONLexer;
import convex.core.json.reader.antlr.JSONParser;
import convex.core.json.reader.antlr.JSONParser.*;
import convex.core.util.JSON;
import convex.core.util.Utils;

/**
 * Reader implementation for pure JSON.
 *
 * Uses ANTLR with a parse listener to build CVM values directly during parsing
 * (no intermediate parse tree). BailErrorStrategy ensures immediate abort on
 * syntax errors without error recovery.
 */
public class JSONReader {

	protected static class JSONListener extends JSONBaseListener {
		ArrayList<ArrayList<ACell>> stack=new ArrayList<>();

		public JSONListener() {
			stack.add(new ArrayList<>());
		}

		/**
		 * Push a cell into the topmost list on the stack
		 * @param a
		 */
		protected void push(ACell a) {
			ArrayList<ACell> top=stack.getLast();
			top.add(a);
		}

		@SuppressWarnings("unchecked")
		protected <R extends ACell> R pop() {
			ArrayList<ACell> top=stack.getLast();
			ACell cell=top.removeLast();
			return (R) cell;
		}

		protected void pushList() {
			stack.add(new ArrayList<>());
		}

		protected ArrayList<ACell> popList() {
			ArrayList<ACell> top=stack.removeLast();
			return top;
		}

		@Override
		public void exitLiteral(LiteralContext ctx) {
			String text=ctx.getText();
			if ("true".equals(text)) {
				push (CVMBool.TRUE);
			} else if ("false".equals(text)) {
				push(CVMBool.FALSE);
			} else {
				push(null);
			}
		}

		@Override
		public void enterArr(ArrContext ctx) {
			pushList(); // We add a new ArrayList to the stack to capture values
		}

		@Override
		public void exitArr(ArrContext ctx) {
			ArrayList<ACell> arr=popList();
			push(Vectors.create(arr));
		}

		@Override
		public void exitNumber(NumberContext ctx) {
			String num=ctx.getText();
			AInteger intv=AInteger.parse(num);
			if (intv!=null) {
				push(intv);
				return;
			}

			try {
				CVMDouble dv=CVMDouble.parse(num);
				if (dv!=null) {
					push(dv);
					return;
				}
			} catch (Exception e) {
				// fall through to exception
			}
			throw new ParseException("Can't parse as number: "+num);
		}

		@Override
		public void exitString(StringContext ctx) {
			String text=ctx.getStart().getText();
			String content=text.substring(1, text.length()-1);
			push(JSON.unescape(content));
		}

		@Override
		public void enterObj(ObjContext ctx) {
			pushList(); // We add a new ArrayList to the stack to capture values
		}

		@Override
		public void exitObj(ObjContext ctx) {
			ArrayList<ACell> arr=popList();
			ACell[] kvs=arr.toArray(Cells.EMPTY_ARRAY);
			push(Maps.create(kvs));
		}
	}

	public static ACell read(String s) {
		return read(CharStreams.fromString(s));
	}

	public static ACell read(java.io.Reader r) throws IOException {
		return read(CharStreams.fromReader(r));
	}

	public static ACell read(InputStream is) throws IOException {
		return read(CharStreams.fromStream(is));
	}

	public static AMap<AString,ACell> readObject(String s) {
		return readObject(CharStreams.fromString(s));
	}

	public static AMap<AString,ACell> readObject(java.io.Reader r) throws IOException {
		return readObject(CharStreams.fromReader(r));
	}

	public static AMap<AString,ACell> readObject(InputStream is) throws IOException {
		CharStream cs=CharStreams.fromStream(is);
		return readObject(cs);
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString,ACell> readObject(CharStream fromStream) {
		ACell a=read(fromStream);
		if (a instanceof AMap object) {
			return object;
		}
		throw new ParseException("Not a JSON object, got: "+Utils.getClassName(a));
	}

	static JSONParser getParser(CharStream cs, JSONListener listener) {
		// Create lexer and parser for the CharStream
		JSONLexer lexer=new JSONLexer(cs);
		lexer.removeErrorListeners();
		lexer.addErrorListener(JSON5Reader.ERROR_LISTENER);
		CommonTokenStream tokens = new CommonTokenStream(lexer);
		JSONParser parser = new JSONParser(tokens);

		// No parse tree needed — listener builds CVM values directly during parsing
		parser.setBuildParseTree(false);
		parser.removeErrorListeners();
		parser.getInterpreter().setPredictionMode(PredictionMode.SLL);
		parser.addErrorListener(JSON5Reader.ERROR_LISTENER);
		// BailErrorStrategy aborts immediately on errors — no recovery attempt
		parser.setErrorHandler(new BailErrorStrategy());

		parser.addParseListener(listener);
		return parser;
	}

	/**
	 * Parse a JSON CharStream into a CVM value.
	 *
	 * ANTLR's BailErrorStrategy throws ParseCancellationException on parse errors.
	 * During exception unwinding, ANTLR's generated finally blocks fire exitRule()
	 * on the parse listener, which can corrupt the listener's stack and throw
	 * NoSuchElementException. Both exception types indicate a parse error and are
	 * converted to ParseException with position information where available.
	 */
	static ACell read(CharStream cs) {
		JSONListener listener=new JSONListener();
		JSONParser parser=getParser(cs,listener);

		try {
			parser.json();
		} catch (ParseCancellationException pe) {
			throw toParseException(pe);
		} catch (NoSuchElementException e) {
			// ANTLR's generated finally blocks fire listener exit events during exception
			// unwinding, which can underflow the listener's stack. The original parse error
			// (ParseCancellationException) is lost because NoSuchElementException replaces
			// it during unwinding. Check suppressed exceptions for the original cause.
			for (Throwable suppressed : e.getSuppressed()) {
				if (suppressed instanceof ParseCancellationException pe) {
					throw toParseException(pe);
				}
			}
			throw new ParseException("JSON parse error (malformed input)",e);
		}

		ArrayList<ACell> top=listener.popList();
		if (top.size()!=1) {
			throw new ParseException("Bad parse output: "+top);
		}

		return top.get(0);
	}

	private static ParseException toParseException(ParseCancellationException pe) {
		Throwable cause=pe.getCause();
		if (cause instanceof RecognitionException re) {
			Token offending=re.getOffendingToken();
			if (offending!=null) {
				return new ParseException("JSON parse error at line "+offending.getLine()+":"+offending.getCharPositionInLine()
					+" near '"+offending.getText()+"'",cause);
			}
		}
		String msg=pe.getMessage();
		return new ParseException(msg!=null ? msg : "JSON parse error",cause);
	}

}
