package convex.core.json;

import java.io.IOException;
import java.util.ArrayList;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.atn.PredictionMode;

import convex.core.data.ACell;
import convex.core.data.prim.CVMBool;
import convex.core.exceptions.ParseException;
import convex.core.json.reader.antlr.JSONBaseListener;
import convex.core.json.reader.antlr.JSONLexer;
import convex.core.json.reader.antlr.JSONParser;
import convex.core.json.reader.antlr.JSONParser.BooleanContext;
import convex.core.json.reader.antlr.JSONParser.NullContext;

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
		public void exitNull(NullContext ctx) { 
			push(null);
		}
		
		@Override 
		public void exitBoolean(BooleanContext ctx) { 
			boolean value= (ctx.getText()).equals("true");
			push(CVMBool.create(value));
		}
	}
	
	public static ACell read(String s) {
		return read(CharStreams.fromString(s));
	}
	
	public static ACell read(java.io.Reader r) throws IOException {
		return read(CharStreams.fromReader(r));
	}
	
	static JSONParser getParser(CharStream cs, JSONListener listener) {
		// Create lexer and paser for the CharStream
		JSONLexer lexer=new JSONLexer(cs);
		lexer.removeErrorListeners();
		// lexer.addErrorListener(ERROR_LISTENER);
		CommonTokenStream tokens = new CommonTokenStream(lexer);
		JSONParser parser = new JSONParser(tokens);
		
		// We don't need a parse tree, just want to visit everything in our listener
		parser.setBuildParseTree(false);
		parser.removeErrorListeners();
		parser.getInterpreter().setPredictionMode(PredictionMode.SLL); // Seems OK for our grammar?
		// parser.addErrorListener(ERROR_LISTENER);

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
