package convex.core.lang.reader;

import java.util.ArrayList;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.antlr.v4.runtime.tree.TerminalNode;

import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.Lists;
import convex.core.data.Sets;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.ParseException;
import convex.core.lang.reader.antlr.ConvexLexer;
import convex.core.lang.reader.antlr.ConvexListener;
import convex.core.lang.reader.antlr.ConvexParser;
import convex.core.lang.reader.antlr.ConvexParser.AddressContext;
import convex.core.lang.reader.antlr.ConvexParser.BoolContext;
import convex.core.lang.reader.antlr.ConvexParser.CharacterContext;
import convex.core.lang.reader.antlr.ConvexParser.DataStructureContext;
import convex.core.lang.reader.antlr.ConvexParser.FormContext;
import convex.core.lang.reader.antlr.ConvexParser.FormsContext;
import convex.core.lang.reader.antlr.ConvexParser.KeywordContext;
import convex.core.lang.reader.antlr.ConvexParser.ListContext;
import convex.core.lang.reader.antlr.ConvexParser.LiteralContext;
import convex.core.lang.reader.antlr.ConvexParser.LongValueContext;
import convex.core.lang.reader.antlr.ConvexParser.NilContext;
import convex.core.lang.reader.antlr.ConvexParser.SetContext;
import convex.core.lang.reader.antlr.ConvexParser.SymbolContext;
import convex.core.lang.reader.antlr.ConvexParser.VectorContext;

public class AntlrReader {
	
	public static class CRListener implements ConvexListener {
		ArrayList<ArrayList<ACell>> stack=new ArrayList<>();
		
		public CRListener() {
			stack.add(new ArrayList<>());
		}
		
		public void push(ACell a) {
			int n=stack.size()-1;
			ArrayList<ACell> top=stack.get(n);
			top.add(a);
		}
		
		public ArrayList<ACell> pop() {
			int n=stack.size()-1;
			ArrayList<ACell> top=stack.get(n);
			stack.remove(n);
			return top;
		}

		@Override
		public void visitTerminal(TerminalNode node) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void visitErrorNode(ErrorNode node) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void enterEveryRule(ParserRuleContext ctx) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void exitEveryRule(ParserRuleContext ctx) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void enterForm(FormContext ctx) {
		}

		@Override
		public void exitForm(FormContext ctx) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void enterForms(FormsContext ctx) {
			// We add a new ArrayList to the stack to capture values
			stack.add(new ArrayList<>());
		}

		@Override
		public void exitForms(FormsContext ctx) {
			// TODO Auto-generated method stub
		}

		@Override
		public void enterDataStructure(DataStructureContext ctx) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void exitDataStructure(DataStructureContext ctx) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void enterList(ListContext ctx) {
			// Nothing to do
		}

		@Override
		public void exitList(ListContext ctx) {
			ArrayList<ACell> elements=pop();
			push(Lists.create(elements));
		}

		@Override
		public void enterVector(VectorContext ctx) {
			// Nothing to do
		}

		@Override
		public void exitVector(VectorContext ctx) {
			ArrayList<ACell> elements=pop();
			push(Vectors.create(elements));
		}

		@Override
		public void enterSet(SetContext ctx) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void exitSet(SetContext ctx) {
			ArrayList<ACell> elements=pop();
			push(Sets.fromCollection(elements));
		}

		@Override
		public void enterLiteral(LiteralContext ctx) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void exitLiteral(LiteralContext ctx) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void enterLongValue(LongValueContext ctx) {
			// TODO Auto-generated method stub
		}

		@Override
		public void exitLongValue(LongValueContext ctx) {
			String s=ctx.getText();
			System.out.println(s);
			push( CVMLong.parse(s));
		}

		@Override
		public void enterNil(NilContext ctx) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void exitNil(NilContext ctx) {
			push(null);
		}

		@Override
		public void enterBool(BoolContext ctx) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void exitBool(BoolContext ctx) {
			push(CVMBool.parse(ctx.getText()));
		}

		@Override
		public void enterCharacter(CharacterContext ctx) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void exitCharacter(CharacterContext ctx) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void enterKeyword(KeywordContext ctx) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void exitKeyword(KeywordContext ctx) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void enterSymbol(SymbolContext ctx) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void exitSymbol(SymbolContext ctx) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void enterAddress(AddressContext ctx) {
			// Nothing
			
		}

		@Override
		public void exitAddress(AddressContext ctx) {
			String s=ctx.getText();
			push (Address.parse(s));
		}
		
	}

	
	public static ACell read(String s) {
		ConvexLexer lexer=new ConvexLexer(CharStreams.fromString(s));
		
		CommonTokenStream tokens = new CommonTokenStream(lexer);
		ConvexParser parser = new ConvexParser(tokens);
		ParseTree tree = parser.form();
		
		CRListener visitor=new CRListener();
		ParseTreeWalker.DEFAULT.walk(visitor, tree);
		
		ArrayList<ACell> top=visitor.pop();
		if (top.size()!=1) {
			throw new ParseException("Bad parse output: "+top+" in code "+s);
		}
		
		return top.get(0);
	}

}
