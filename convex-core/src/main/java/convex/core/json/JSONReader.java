package convex.core.json;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.atn.PredictionMode;

import convex.core.data.ACell;
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
import convex.core.lang.reader.ConvexErrorListener;
import convex.core.util.JSONUtils;

/**
 * Reader implementation for pure JSON
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
				
			}
			throw new ParseException("Can't parse as number: "+num);
		}
		
		@Override
		public void exitString(StringContext ctx) {
			String text=ctx.getText();
			String content=text.substring(1, text.length()-1);
			push(JSONUtils.unescape(content));
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

	
	private static final ConvexErrorListener ERROR_LISTENER=new ConvexErrorListener();

	
	static JSONParser getParser(CharStream cs, JSONListener listener) {
		// Create lexer and paser for the CharStream
		JSONLexer lexer=new JSONLexer(cs);
		lexer.removeErrorListeners();
		lexer.addErrorListener(ERROR_LISTENER);
		CommonTokenStream tokens = new CommonTokenStream(lexer);
		JSONParser parser = new JSONParser(tokens);
		
		// We don't need a parse tree, just want to visit everything in our listener
		parser.setBuildParseTree(false);
		parser.removeErrorListeners();
		parser.getInterpreter().setPredictionMode(PredictionMode.SLL); // Seems OK for our grammar?
		parser.addErrorListener(ERROR_LISTENER);

		parser.addParseListener(listener);	
		return parser;
	}
	
	static ACell read(CharStream cs) {
		JSONListener listener=new JSONListener();
		JSONParser parser=getParser(cs,listener);
		
		parser.json();
		
		ArrayList<ACell> top=listener.popList();
		if (top.size()!=1) {
			throw new ParseException("Bad parse output: "+top);
		}
		
		return top.get(0);
	}

}
