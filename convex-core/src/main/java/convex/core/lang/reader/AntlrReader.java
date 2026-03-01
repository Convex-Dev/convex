package convex.core.lang.reader;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.NoSuchElementException;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeListener;

import convex.core.Constants;
import convex.core.cvm.Address;
import convex.core.cvm.CVMEncoder;
import convex.core.cvm.Symbols;
import convex.core.cvm.Syntax;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AList;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Keyword;
import convex.core.data.List;
import convex.core.data.Lists;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.Symbol;
import convex.core.data.Vectors;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMChar;
import convex.core.data.prim.CVMDouble;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.ParseException;
import convex.core.lang.RT;
import convex.core.lang.reader.antlr.ConvexBaseListener;
import convex.core.lang.reader.antlr.ConvexLexer;
import convex.core.lang.reader.antlr.ConvexParser;
import convex.core.lang.reader.antlr.ConvexParser.AddressContext;
import convex.core.lang.reader.antlr.ConvexParser.AllFormsContext;
import convex.core.lang.reader.antlr.ConvexParser.BlobContext;
import convex.core.lang.reader.antlr.ConvexParser.BoolContext;
import convex.core.lang.reader.antlr.ConvexParser.Cad3Context;
import convex.core.lang.reader.antlr.ConvexParser.CharacterContext;
import convex.core.lang.reader.antlr.ConvexParser.CommentedContext;
import convex.core.lang.reader.antlr.ConvexParser.DoubleValueContext;
import convex.core.lang.reader.antlr.ConvexParser.ImplicitSymbolContext;
import convex.core.lang.reader.antlr.ConvexParser.KeywordContext;
import convex.core.lang.reader.antlr.ConvexParser.ListContext;
import convex.core.lang.reader.antlr.ConvexParser.LongValueContext;
import convex.core.lang.reader.antlr.ConvexParser.MapContext;
import convex.core.lang.reader.antlr.ConvexParser.NilContext;
import convex.core.lang.reader.antlr.ConvexParser.PathSymbolContext;
import convex.core.lang.reader.antlr.ConvexParser.QuotedContext;
import convex.core.lang.reader.antlr.ConvexParser.ResolveContext;
import convex.core.lang.reader.antlr.ConvexParser.SetContext;
import convex.core.lang.reader.antlr.ConvexParser.SlashSymbolContext;
import convex.core.lang.reader.antlr.ConvexParser.SpecialLiteralContext;
import convex.core.lang.reader.antlr.ConvexParser.StringContext;
import convex.core.lang.reader.antlr.ConvexParser.SymbolContext;
import convex.core.lang.reader.antlr.ConvexParser.SyntaxContext;
import convex.core.lang.reader.antlr.ConvexParser.TagContext;
import convex.core.lang.reader.antlr.ConvexParser.TaggedFormContext;
import convex.core.lang.reader.antlr.ConvexParser.VectorContext;
import convex.core.text.Text;
import convex.core.util.Utils;

/**
 * Reader for Convex CVX format. Basically stringified CAD3 with some CVM-specific features.
 */
public class AntlrReader {
	
	private static final int MAX_TOKEN_DISPLAY = 20;
	private static final String KEYWORD_TOO_LONG = "Keyword too long (max "+Constants.MAX_NAME_LENGTH+"): ";
	private static final String SYMBOL_TOO_LONG = "Symbol too long (max "+Constants.MAX_NAME_LENGTH+"): ";

	/**
	 * Truncate a token string for safe inclusion in error messages.
	 * Prevents OOM from constructing huge error strings for malicious input.
	 */
	static String truncate(String s) {
		if (s.length()<=MAX_TOKEN_DISPLAY) return s;
		return s.substring(0,MAX_TOKEN_DISPLAY)+"...";
	}

	/**
	 * Create a ParseException with position info from an ANTLR context
	 */
	static ParseException parseError(ParserRuleContext ctx, String msg) {
		Token tok=ctx.getStart();
		return new ParseException("Parse error at "+tok.getLine()+":"+tok.getCharPositionInLine()+": "+msg);
	}

	static class CRListener extends ConvexBaseListener {
		ArrayList<ArrayList<ACell>> stack=new ArrayList<>();
		protected CVMEncoder encoder;

		public CRListener() {
			this (CVMEncoder.INSTANCE);
		}

		public CRListener(CVMEncoder encoder) {
			this.encoder=encoder;
			stack.add(new ArrayList<>());
		}
		
		/**
		 * Push a cell into the topmost list on the stack
		 * @param a Value to push on stack
		 */
		public void push(ACell a) {
			ArrayList<ACell> top=stack.getLast();
			top.add(a);
		}
		
		@SuppressWarnings("unchecked")
		public <R extends ACell> R pop() {
			ArrayList<ACell> top=stack.getLast();
			ACell cell=top.removeLast();
			return (R) cell;
		}

		
		private void pushList() {
			stack.add(new ArrayList<>());
		}
		
		public ArrayList<ACell> popList() {
			ArrayList<ACell> top=stack.removeLast();
			return top;
		}

		@Override
		public void visitErrorNode(ErrorNode node) {
			Token tok=node.getSymbol();
			throw new ParseException("Parse error at "+tok.getLine()+":"+tok.getCharPositionInLine()
				+": unexpected '"+truncate(tok.getText())+"'");
		}

		@Override
		public void enterList(ListContext ctx) {
			pushList(); // We add a new ArrayList to the stack to capture values
		}

		@Override
		public void exitList(ListContext ctx) {
			ArrayList<ACell> elements=popList();
			push(Lists.create(elements));
		}

		@Override
		public void enterVector(VectorContext ctx) {
			pushList(); // We add a new ArrayList to the stack to capture values
		}

		@Override
		public void exitVector(VectorContext ctx) {
			ArrayList<ACell> elements=popList();
			push(Vectors.create(elements));
		}

		@Override
		public void enterSet(SetContext ctx) {
			pushList(); // We add a new ArrayList to the stack to capture values
		}

		@Override
		public void exitSet(SetContext ctx) {
			ArrayList<ACell> elements=popList();
			push(Sets.fromCollection(elements));
		}
		
		@Override
		public void enterMap(MapContext ctx) {
			pushList(); // We add a new ArrayList to the stack to capture values
		}

		@Override
		public void exitMap(MapContext ctx) {
			ArrayList<ACell> elements=popList();
			if (Utils.isOdd(elements.size())) {
				throw parseError(ctx,"map requires an even number of forms");
			}
			push(Maps.create(elements.toArray(new ACell[elements.size()])));
		}

		@Override
		public void exitLongValue(LongValueContext ctx) {
			// Just looking at the last token probably most efficient way to get string?
			String s=ctx.getStop().getText();
			AInteger a= AInteger.parse(s);
			if (a==null) throw parseError(ctx,"bad number format: "+truncate(s));
			push(a);
		}
		
		@Override
		public void enterDoubleValue(DoubleValueContext ctx) {
			// Nothing to do
		}

		@Override
		public void exitDoubleValue(DoubleValueContext ctx) {
			String s=ctx.getStop().getText();
			CVMDouble v=CVMDouble.parse(s);
			if (v==null) throw parseError(ctx,"bad double format: "+truncate(s));
			push(v);	
		}

		@Override
		public void exitNil(NilContext ctx) {
			push(null);
		}

		@Override
		public void exitBool(BoolContext ctx) {
			push(CVMBool.parse(ctx.getStop().getText()));
		}

		@Override
		public void exitCharacter(CharacterContext ctx) {
			String s=ctx.getStop().getText();
			CVMChar c=CVMChar.parse(s);
			if (c==null) throw parseError(ctx,"bad character literal: "+s);
			push(c);
		}

		@Override
		public void exitKeyword(KeywordContext ctx) {
			String s=ctx.getStop().getText();
			Keyword k=Keyword.create(s.substring(1));
			if (k==null) throw parseError(ctx,KEYWORD_TOO_LONG+truncate(s));
			push( k);
		}

		@Override
		public void exitSymbol(SymbolContext ctx) {
			String s=ctx.getStop().getText();
			Symbol sym=Symbol.create(s);
			if (sym==null) throw parseError(ctx,SYMBOL_TOO_LONG+truncate(s));
			push( sym);
		}

		@Override
		public void exitImplicitSymbol(ImplicitSymbolContext ctx) {
			String s=ctx.getText();
			Symbol sym=Symbol.create(s);
			if (sym==null) throw parseError(ctx,"bad implicit symbol: "+s);
			push( sym);
		}
		
		@Override
		public void enterTaggedForm(TaggedFormContext ctx) {
			pushList();
		}

		@Override
		public void exitTaggedForm(TaggedFormContext ctx) {
			ArrayList<ACell> elements=popList();
			if (elements.size()!=2) throw parseError(ctx,"tagged form requires tag and form but got: "+ elements);
			Symbol sym=(Symbol) elements.get(0);
			ACell value=elements.get(1);
			
			ACell result=Cells.createTagged(sym,value);
			push(result);
		}

		@Override
		public void exitTag(TagContext ctx) {
			String s=ctx.getText();
			s=s.substring(1); // skip leading #
			Symbol sym=Symbol.create(s);
			if (sym==null) throw parseError(ctx,"bad tag: #"+s);
			push( sym);
		}

		@Override
		public void exitAddress(AddressContext ctx) {
			String s=ctx.getStop().getText();
			try {
				long value=Long.parseLong(s.substring(1));
				Address addr=Address.create(value);
				if (addr==null) throw parseError(ctx,"bad address format: "+s);
				push (addr);
			} catch (NumberFormatException e) {
				throw parseError(ctx,"bad address format: "+s);
			}
		}

		@Override
		public void enterSyntax(SyntaxContext ctx) {
			// add new list to collect [syntax, value]
			pushList();
		}


		@Override
		public void exitSyntax(SyntaxContext ctx) {
			ArrayList<ACell> elements=popList();
			if (elements.size()!=2) throw parseError(ctx,"metadata requires metadata and annotated form but got: "+ elements);
			AHashMap<ACell,ACell> meta=ReaderUtils.interpretMetadata(elements.get(0));
			ACell value=elements.get(1);
			push(Syntax.create(value, meta));
		}

		@Override
		public void exitBlob(BlobContext ctx) {
			String s=ctx.getStop().getText();
			Blob b=Blob.fromHex(s.substring(2));
			if (b==null) throw parseError(ctx,"invalid blob syntax: "+truncate(s));
			push(b);
		}

		@Override
		public void exitCad3(Cad3Context ctx) {
			String s=ctx.getStop().getText();
			Blob enc=Blob.fromHex(s.substring(2, s.length()-1));
			try {
				ACell cell=encoder.decodeMultiCell(enc);
				push (cell);
			} catch (BadFormatException e) {
				throw parseError(ctx,"invalid CAD3 encoding: "+e.getMessage());
			}
		}

		@Override
		public void exitQuoted(QuotedContext ctx) {
			ACell form=pop();
			String qs=ctx.getStart().getText();
			Symbol qsym=ReaderUtils.getQuotingSymbol(qs);
			if (qsym==null) throw parseError(ctx,"invalid quoting reader macro: "+qs);
			push(Lists.of(qsym,form));	
		}

		@Override
		public void exitResolve(ResolveContext ctx) {
			String s=ctx.getStop().getText();
			s=s.substring(1); // skip leading @
			Symbol sym=Symbol.create(s);
			if (sym==null) throw parseError(ctx,"invalid @ symbol: @"+s);
			push(List.of(Symbols.RESOLVE,sym));
		}
		

		@Override
		public void exitSlashSymbol(SlashSymbolContext ctx) {
			String s=ctx.getStop().getText();
			s=s.substring(1); // skip leading /
			Symbol sym=Symbol.create(s);
			if (sym==null) throw parseError(ctx,"invalid path symbol: /"+s);
			push(sym);
		}

		@Override
		public void exitString(StringContext ctx) {
			String s=ctx.getStop().getText();
			int n=s.length();
			s=s.substring(1, n-1); // skip surrounding double quotes
			s=Text.unescapeJava(s);
			push(Strings.create(s));
		}

		@Override
		public void exitSpecialLiteral(SpecialLiteralContext ctx) {
			String s=ctx.getStop().getText();
			ACell special=ReaderUtils.specialLiteral(s);
			if (special==null) throw parseError(ctx,"invalid special literal: "+s);
			push(special);
		}

		@Override
		public void enterCommented(CommentedContext ctx) {
			// make a dummy list, doesn't matter what goes in here
			pushList();
		}

		@Override
		public void exitCommented(CommentedContext ctx) {
			// remove commented form
			popList();	
		}

		@Override
		public void enterPathSymbol(PathSymbolContext ctx) {
			// Add a list to accumulate values
			pushList();
		}

		@Override
		public void exitPathSymbol(PathSymbolContext ctx) {
			ArrayList<ACell> elements=popList();
			int n=elements.size();
			
			ACell lookup=elements.get(0);
			if (lookup==null) throw parseError(ctx,"path must start with address or symbol");
			
			for (int i=1; i<n; i++) {
				ACell sym=elements.get(i);
				
				if (!(sym instanceof Symbol)) throw parseError(ctx,"expected path element to be a symbol but got: "+ RT.getType(sym));
				// System.out.println(elements);
				lookup=List.create(Symbols.LOOKUP,lookup,sym);
			}
			push(lookup);
		}

		@Override
		public void enterAllForms(AllFormsContext ctx) {
			// Add a new list to stack to capture all forms. readAll() will pop this
			pushList(); 
		}
	}
	
	public static ACell read(InputStream is) throws IOException {
		return read(CharStreams.fromStream(is));
	}


	public static ACell read(String s) {
		if (s==null) throw new ParseException("Null input String");
		return read(CharStreams.fromString(s));
	}
	
	public static ACell read(java.io.Reader r) throws IOException {
		return read(CharStreams.fromReader(r));
	}
	
	private static final ConvexErrorListener ERROR_LISTENER=new ConvexErrorListener();
	
	// Recommended in https://github.com/antlr/antlr4/blob/dev/doc/listeners.md
	static class CatchingParser extends ConvexParser {
		protected boolean listenerExceptionOccurred = false;
		public CatchingParser(TokenStream input) {
			super(input);
		}
		
		@Override
		protected void triggerExitRuleEvent() {
			if ( listenerExceptionOccurred ) return;
			try {
				// reverse order walk of listeners
				for (int i = _parseListeners.size() - 1; i >= 0; i--) {
					ParseTreeListener listener = _parseListeners.get(i);
					_ctx.exitRule(listener);
					listener.exitEveryRule(_ctx);
				}
			}
			catch (ParseException e) {
				// If an exception is thrown in the user's listener code, we need to bail out
				// completely out of the parser, without executing anymore user code. We
				// must also stop the parse otherwise other listener actions will attempt to execute
				// almost certainly with invalid results. So, record the fact an exception occurred
				listenerExceptionOccurred = true;
				throw e;
			}
			catch (NoSuchElementException e) {
				// Listener stack underflow due to malformed input (e.g. lone quote character).
				listenerExceptionOccurred = true;
				Token tok=getCurrentToken();
				String desc=(tok!=null&&"<EOF>".equals(tok.getText())) ? "unexpected end of input" : "unexpected token";
				throw new ParseException("Parse error at "
					+(tok!=null ? tok.getLine()+":"+tok.getCharPositionInLine() : "unknown position")
					+": "+desc,e);
			}
		}
		
	}
	
	/**
	 * Dump lexer tokens for debugging. Not used in production.
	 */
	public static void dumpTokens(String input) {
		ConvexLexer lexer=new ConvexLexer(CharStreams.fromString(input));
		CommonTokenStream tokens = new CommonTokenStream(lexer);
		tokens.fill();
		for (Token t : tokens.getTokens()) {
			System.out.println("  " + ConvexLexer.VOCABULARY.getSymbolicName(t.getType())
				+ " '" + t.getText() + "' at " + t.getLine()+":"+t.getStartIndex()+"-"+t.getStopIndex());
		}
	}

	static ConvexParser getParser(CharStream cs, CRListener listener) {
		// Create lexer and paser for the CharStream
		ConvexLexer lexer=new ConvexLexer(cs);
		lexer.removeErrorListeners();
		lexer.addErrorListener(ERROR_LISTENER);
		CommonTokenStream tokens = new CommonTokenStream(lexer);
		ConvexParser parser = new CatchingParser(tokens);
		
		// We don't need a parse tree, just want to visit everything in our listener
		parser.setBuildParseTree(false);
		parser.removeErrorListeners();
		parser.getInterpreter().setPredictionMode(PredictionMode.SLL); // Seems OK for our grammar?
		parser.addErrorListener(ERROR_LISTENER);

		parser.addParseListener(listener);	
		return parser;
	}
	
	static ACell read(CharStream cs) {
		CRListener listener=new CRListener();
		ConvexParser parser=getParser(cs,listener);
		
		parser.singleForm();
		
		ArrayList<ACell> top=listener.popList();
		if (top.size()!=1) {
			throw new ParseException("Bad parse output: "+top);
		}
		
		return top.get(0);
	}
	
	public static AList<ACell> readAll(String source) {
		return readAll(CharStreams.fromString(source));
	}

	static AList<ACell> readAll(CharStream cs) {
		CRListener listener=new CRListener();
		ConvexParser parser=getParser(cs,listener);
		
		parser.allForms();
		
		ArrayList<ACell> top=listener.popList();
		return Lists.create(top);
	}

	static ParseTree getParseTree(String input) {
		CharStream cs=CharStreams.fromString(input);
		return getParseTree(cs);
	}
	
	static ParseTree getParseTree(CharStream cs) {
		ConvexLexer lexer=new ConvexLexer(cs);
		lexer.removeErrorListeners();
		lexer.addErrorListener(new ConvexErrorListener() );
		CommonTokenStream tokens = new CommonTokenStream(lexer);
		ConvexParser parser = new ConvexParser(tokens);
		parser.removeErrorListeners();
		ParseTree tree = parser.allForms();
		return tree;
	}

	public static Lexer getLexer(CharStream cs) {
		return new ConvexLexer(cs);
	}

}
