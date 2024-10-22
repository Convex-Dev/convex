package convex.core.lang.reader;

import java.io.IOException;
import java.util.ArrayList;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.TerminalNode;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AList;
import convex.core.data.Address;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Keyword;
import convex.core.data.List;
import convex.core.data.Lists;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.Symbol;
import convex.core.data.Symbols;
import convex.core.data.Syntax;
import convex.core.data.Vectors;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMChar;
import convex.core.data.prim.CVMDouble;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.ParseException;
import convex.core.lang.RT;
import convex.core.lang.reader.antlr.ConvexLexer;
import convex.core.lang.reader.antlr.ConvexListener;
import convex.core.lang.reader.antlr.ConvexParser;
import convex.core.lang.reader.antlr.ConvexParser.AddressContext;
import convex.core.lang.reader.antlr.ConvexParser.AllFormsContext;
import convex.core.lang.reader.antlr.ConvexParser.AtomContext;
import convex.core.lang.reader.antlr.ConvexParser.BlobContext;
import convex.core.lang.reader.antlr.ConvexParser.BoolContext;
import convex.core.lang.reader.antlr.ConvexParser.Cad3Context;
import convex.core.lang.reader.antlr.ConvexParser.CharacterContext;
import convex.core.lang.reader.antlr.ConvexParser.CommentedContext;
import convex.core.lang.reader.antlr.ConvexParser.DataStructureContext;
import convex.core.lang.reader.antlr.ConvexParser.DoubleValueContext;
import convex.core.lang.reader.antlr.ConvexParser.FormContext;
import convex.core.lang.reader.antlr.ConvexParser.FormsContext;
import convex.core.lang.reader.antlr.ConvexParser.ImplicitSymbolContext;
import convex.core.lang.reader.antlr.ConvexParser.KeywordContext;
import convex.core.lang.reader.antlr.ConvexParser.ListContext;
import convex.core.lang.reader.antlr.ConvexParser.LiteralContext;
import convex.core.lang.reader.antlr.ConvexParser.LongValueContext;
import convex.core.lang.reader.antlr.ConvexParser.MapContext;
import convex.core.lang.reader.antlr.ConvexParser.NilContext;
import convex.core.lang.reader.antlr.ConvexParser.PathSymbolContext;
import convex.core.lang.reader.antlr.ConvexParser.PrimaryContext;
import convex.core.lang.reader.antlr.ConvexParser.QuotedContext;
import convex.core.lang.reader.antlr.ConvexParser.ResolveContext;
import convex.core.lang.reader.antlr.ConvexParser.SetContext;
import convex.core.lang.reader.antlr.ConvexParser.SingleFormContext;
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
 
public class AntlrReader {
	
	static class CRListener implements ConvexListener {
		ArrayList<ArrayList<ACell>> stack=new ArrayList<>();
		
		public CRListener() {
			stack.add(new ArrayList<>());
		}
		
		/**
		 * Push a cell into the topmost list on the stack
		 * @param a
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
		public void visitTerminal(TerminalNode node) {
			// Nothing to do
		}

		@Override
		public void visitErrorNode(ErrorNode node) {
			throw new ParseException(node.getSourceInterval()+" "+node.getText());
		}

		@Override
		public void enterEveryRule(ParserRuleContext ctx) {
			// Nothing to do
		}

		@Override
		public void exitEveryRule(ParserRuleContext ctx) {
			// Nothing to do
		}

		@Override
		public void enterForm(FormContext ctx) {
			// Nothing to do
		}

		@Override
		public void exitForm(FormContext ctx) {
			// Nothing to do
		}

		@Override
		public void enterForms(FormsContext ctx) {
			// Nothing to do
		}

		@Override
		public void exitForms(FormsContext ctx) {
			// Nothing to do
		}

		@Override
		public void enterDataStructure(DataStructureContext ctx) {
			// Nothing to do
		}

		@Override
		public void exitDataStructure(DataStructureContext ctx) {
			// Nothing to do
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
				throw new ParseException("Map requires an even number of forms.");
			}
			push(Maps.create(elements.toArray(new ACell[elements.size()])));
		}

		@Override
		public void enterLiteral(LiteralContext ctx) {
			// Nothing to do
		}

		@Override
		public void exitLiteral(LiteralContext ctx) {
			// Nothing to do
		}

		@Override
		public void enterLongValue(LongValueContext ctx) {
			// Nothing to do
		}

		@Override
		public void exitLongValue(LongValueContext ctx) {
			// Just looking at the last token probably most efficient way to get string?
			String s=ctx.getStop().getText();
			AInteger a= AInteger.parse(s);
			if (a==null) throw new ParseException("Unparseable number: "+s);
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
			if (v==null) throw new ParseException("Bad double format: "+s);
			push(v);	
		}

		@Override
		public void enterNil(NilContext ctx) {
			// Nothing to do
		}

		@Override
		public void exitNil(NilContext ctx) {
			push(null);
		}

		@Override
		public void enterBool(BoolContext ctx) {
			// Nothing to do
		}

		@Override
		public void exitBool(BoolContext ctx) {
			push(CVMBool.parse(ctx.getStop().getText()));
		}

		@Override
		public void enterCharacter(CharacterContext ctx) {
			// Nothing
		}

		@Override
		public void exitCharacter(CharacterContext ctx) {
			String s=ctx.getStop().getText();
			CVMChar c=CVMChar.parse(s);
			if (c==null) throw new ParseException("Bad character literal format: "+s);
			push(c);
		}

		@Override
		public void enterKeyword(KeywordContext ctx) {
			// Nothing to do
		}

		@Override
		public void exitKeyword(KeywordContext ctx) {
			String s=ctx.getStop().getText();
			Keyword k=Keyword.create(s.substring(1));
			if (k==null) throw new ParseException("Bad Keyword format: "+s);
			push( k);
		}

		@Override
		public void enterSymbol(SymbolContext ctx) {
			// Nothing to do
		}

		@Override
		public void exitSymbol(SymbolContext ctx) {
			String s=ctx.getStop().getText();
			Symbol sym=Symbol.create(s);
			if (sym==null) throw new ParseException("Bad Symbol format: "+s);
			push( sym);
		}
		
		@Override
		public void enterImplicitSymbol(ImplicitSymbolContext ctx) {
			// Nothing to do
		}

		@Override
		public void exitImplicitSymbol(ImplicitSymbolContext ctx) {
			String s=ctx.getText();
			Symbol sym=Symbol.create(s);
			if (sym==null) throw new ParseException("Bad implicit symbol: "+s);
			push( sym);
		}
		
		@Override
		public void enterTaggedForm(TaggedFormContext ctx) {
			pushList();
		}

		@Override
		public void exitTaggedForm(TaggedFormContext ctx) {
			ArrayList<ACell> elements=popList();
			if (elements.size()!=2) throw new ParseException("Tagged form tag and form but got:"+ elements);
			Symbol sym=(Symbol) elements.get(0);
			ACell value=elements.get(1);
			
			ACell result=Cells.createTagged(sym,value);
			push(result);
		}

		@Override
		public void enterTag(TagContext ctx) {
			// Nothing to do
		}

		@Override
		public void exitTag(TagContext ctx) {
			String s=ctx.getText();
			s=s.substring(1); // skip leading #
			Symbol sym=Symbol.create(s);
			if (sym==null) throw new ParseException("Bad tag: #"+s);
			push( sym);
		}

		@Override
		public void enterAddress(AddressContext ctx) {
			// Nothing to do
		}

		@Override
		public void exitAddress(AddressContext ctx) {
			String s=ctx.getStop().getText();
			try {
				long value=Long.parseLong(s.substring(1));
				Address addr=Address.create(value);
				if (addr==null) throw new ParseException("Bad Address format: "+s);
				push (addr);
			} catch (NumberFormatException e) {
				throw new ParseException("Problem parsing Address: "+s,e);
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
			if (elements.size()!=2) throw new ParseException("Metadata requires metadata and annotated form but got:"+ elements);
			AHashMap<ACell,ACell> meta=ReaderUtils.interpretMetadata(elements.get(0));
			ACell value=elements.get(1);
			push(Syntax.create(value, meta));
		}

		@Override
		public void enterBlob(BlobContext ctx) {
			// Nothing to do
			
		}

		@Override
		public void exitBlob(BlobContext ctx) {
			String s=ctx.getStop().getText();
			Blob b=Blob.fromHex(s.substring(2));
			if (b==null) throw new ParseException("Invalid Blob syntax: "+s);
			push(b);
		}
		
		@Override
		public void enterCad3(Cad3Context ctx) {
			// nothing to do
		}

		@Override
		public void exitCad3(Cad3Context ctx) {
			String s=ctx.getStop().getText();
			Blob enc=Blob.fromHex(s.substring(2, s.length()-1));
			try {
				ACell cell=convex.core.data.Format.decodeMultiCell(enc);
				push (cell);
			} catch (BadFormatException e) {
				throw new ParseException("Invalid CAD3 encoding: "+e.getMessage(),e);
			}
		}

		@Override
		public void enterQuoted(QuotedContext ctx) {
			// Nothing to do
		}

		@Override
		public void exitQuoted(QuotedContext ctx) {
			ACell form=pop();
			String qs=ctx.getStart().getText();
			Symbol qsym=ReaderUtils.getQuotingSymbol(qs);
			if (qsym==null) throw new ParseException("Invalid quoting reader macro: "+qs);
			push(Lists.of(qsym,form));	
		}
		
		@Override
		public void enterResolve(ResolveContext ctx) {
			// Nothing to do
		}

		@Override
		public void exitResolve(ResolveContext ctx) {
			String s=ctx.getStop().getText();
			s=s.substring(1); // skip leading @
			Symbol sym=Symbol.create(s);
			if (sym==null) throw new ParseException("Invalid @ symbol: @"+s);
			push(List.of(Symbols.RESOLVE,sym));
		}
		

		@Override
		public void enterSlashSymbol(SlashSymbolContext ctx) {
			// Nothing to do
		}

		@Override
		public void exitSlashSymbol(SlashSymbolContext ctx) {
			String s=ctx.getStop().getText();
			s=s.substring(1); // skip leading /
			Symbol sym=Symbol.create(s);
			if (sym==null) throw new ParseException("Invalid / symbol: /"+s);
			push(sym);
		}

		@Override
		public void enterString(StringContext ctx) {
			// Nothing to do
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
		public void enterSpecialLiteral(SpecialLiteralContext ctx) {
			// Nothing to do
		}

		@Override
		public void exitSpecialLiteral(SpecialLiteralContext ctx) {
			String s=ctx.getStop().getText();
			ACell special=ReaderUtils.specialLiteral(s);
			if (special==null) throw new ParseException("Invalid special literal: "+s);
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
			if (lookup==null) throw new ParseException("Path must start with Address or Symbol");
			
			for (int i=1; i<n; i++) {
				ACell sym=elements.get(i);
				
				if (!(sym instanceof Symbol)) throw new ParseException("Expected path element to be a symbol but got: "+ RT.getType(sym));
				// System.out.println(elements);
				lookup=List.create(Symbols.LOOKUP,lookup,sym);
			}
			push(lookup);
		}

		@Override
		public void enterSingleForm(SingleFormContext ctx) {
			// Nothing	
		}

		@Override
		public void exitSingleForm(SingleFormContext ctx) {
			// Nothing
		}

		@Override
		public void enterAllForms(AllFormsContext ctx) {
			// Add a new list to stack to capture all forms. readAll() will pop this
			pushList(); 
		}

		@Override
		public void exitAllForms(AllFormsContext ctx) {
			// Nothing	
		}

		@Override
		public void enterAtom(AtomContext ctx) {
			// Nothing	
		}

		@Override
		public void exitAtom(AtomContext ctx) {
			// Nothing	
		}

		@Override
		public void enterPrimary(PrimaryContext ctx) {
			// Nothing
			
		}

		@Override
		public void exitPrimary(PrimaryContext ctx) {
			// Nothing
		}


	}

	public static ACell read(String s) {
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
