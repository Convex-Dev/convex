// Generated from convex/core/lang/reader/antlr/Convex.g4 by ANTLR 4.9.2
package convex.core.lang.reader.antlr;
import org.antlr.v4.runtime.tree.ParseTreeListener;

/**
 * This interface defines a complete listener for a parse tree produced by
 * {@link ConvexParser}.
 */
public interface ConvexListener extends ParseTreeListener {
	/**
	 * Enter a parse tree produced by {@link ConvexParser#form}.
	 * @param ctx the parse tree
	 */
	void enterForm(ConvexParser.FormContext ctx);
	/**
	 * Exit a parse tree produced by {@link ConvexParser#form}.
	 * @param ctx the parse tree
	 */
	void exitForm(ConvexParser.FormContext ctx);
	/**
	 * Enter a parse tree produced by {@link ConvexParser#singleForm}.
	 * @param ctx the parse tree
	 */
	void enterSingleForm(ConvexParser.SingleFormContext ctx);
	/**
	 * Exit a parse tree produced by {@link ConvexParser#singleForm}.
	 * @param ctx the parse tree
	 */
	void exitSingleForm(ConvexParser.SingleFormContext ctx);
	/**
	 * Enter a parse tree produced by {@link ConvexParser#forms}.
	 * @param ctx the parse tree
	 */
	void enterForms(ConvexParser.FormsContext ctx);
	/**
	 * Exit a parse tree produced by {@link ConvexParser#forms}.
	 * @param ctx the parse tree
	 */
	void exitForms(ConvexParser.FormsContext ctx);
	/**
	 * Enter a parse tree produced by {@link ConvexParser#dataStructure}.
	 * @param ctx the parse tree
	 */
	void enterDataStructure(ConvexParser.DataStructureContext ctx);
	/**
	 * Exit a parse tree produced by {@link ConvexParser#dataStructure}.
	 * @param ctx the parse tree
	 */
	void exitDataStructure(ConvexParser.DataStructureContext ctx);
	/**
	 * Enter a parse tree produced by {@link ConvexParser#list}.
	 * @param ctx the parse tree
	 */
	void enterList(ConvexParser.ListContext ctx);
	/**
	 * Exit a parse tree produced by {@link ConvexParser#list}.
	 * @param ctx the parse tree
	 */
	void exitList(ConvexParser.ListContext ctx);
	/**
	 * Enter a parse tree produced by {@link ConvexParser#vector}.
	 * @param ctx the parse tree
	 */
	void enterVector(ConvexParser.VectorContext ctx);
	/**
	 * Exit a parse tree produced by {@link ConvexParser#vector}.
	 * @param ctx the parse tree
	 */
	void exitVector(ConvexParser.VectorContext ctx);
	/**
	 * Enter a parse tree produced by {@link ConvexParser#set}.
	 * @param ctx the parse tree
	 */
	void enterSet(ConvexParser.SetContext ctx);
	/**
	 * Exit a parse tree produced by {@link ConvexParser#set}.
	 * @param ctx the parse tree
	 */
	void exitSet(ConvexParser.SetContext ctx);
	/**
	 * Enter a parse tree produced by {@link ConvexParser#map}.
	 * @param ctx the parse tree
	 */
	void enterMap(ConvexParser.MapContext ctx);
	/**
	 * Exit a parse tree produced by {@link ConvexParser#map}.
	 * @param ctx the parse tree
	 */
	void exitMap(ConvexParser.MapContext ctx);
	/**
	 * Enter a parse tree produced by {@link ConvexParser#literal}.
	 * @param ctx the parse tree
	 */
	void enterLiteral(ConvexParser.LiteralContext ctx);
	/**
	 * Exit a parse tree produced by {@link ConvexParser#literal}.
	 * @param ctx the parse tree
	 */
	void exitLiteral(ConvexParser.LiteralContext ctx);
	/**
	 * Enter a parse tree produced by {@link ConvexParser#longValue}.
	 * @param ctx the parse tree
	 */
	void enterLongValue(ConvexParser.LongValueContext ctx);
	/**
	 * Exit a parse tree produced by {@link ConvexParser#longValue}.
	 * @param ctx the parse tree
	 */
	void exitLongValue(ConvexParser.LongValueContext ctx);
	/**
	 * Enter a parse tree produced by {@link ConvexParser#doubleValue}.
	 * @param ctx the parse tree
	 */
	void enterDoubleValue(ConvexParser.DoubleValueContext ctx);
	/**
	 * Exit a parse tree produced by {@link ConvexParser#doubleValue}.
	 * @param ctx the parse tree
	 */
	void exitDoubleValue(ConvexParser.DoubleValueContext ctx);
	/**
	 * Enter a parse tree produced by {@link ConvexParser#specialLiteral}.
	 * @param ctx the parse tree
	 */
	void enterSpecialLiteral(ConvexParser.SpecialLiteralContext ctx);
	/**
	 * Exit a parse tree produced by {@link ConvexParser#specialLiteral}.
	 * @param ctx the parse tree
	 */
	void exitSpecialLiteral(ConvexParser.SpecialLiteralContext ctx);
	/**
	 * Enter a parse tree produced by {@link ConvexParser#address}.
	 * @param ctx the parse tree
	 */
	void enterAddress(ConvexParser.AddressContext ctx);
	/**
	 * Exit a parse tree produced by {@link ConvexParser#address}.
	 * @param ctx the parse tree
	 */
	void exitAddress(ConvexParser.AddressContext ctx);
	/**
	 * Enter a parse tree produced by {@link ConvexParser#nil}.
	 * @param ctx the parse tree
	 */
	void enterNil(ConvexParser.NilContext ctx);
	/**
	 * Exit a parse tree produced by {@link ConvexParser#nil}.
	 * @param ctx the parse tree
	 */
	void exitNil(ConvexParser.NilContext ctx);
	/**
	 * Enter a parse tree produced by {@link ConvexParser#blob}.
	 * @param ctx the parse tree
	 */
	void enterBlob(ConvexParser.BlobContext ctx);
	/**
	 * Exit a parse tree produced by {@link ConvexParser#blob}.
	 * @param ctx the parse tree
	 */
	void exitBlob(ConvexParser.BlobContext ctx);
	/**
	 * Enter a parse tree produced by {@link ConvexParser#bool}.
	 * @param ctx the parse tree
	 */
	void enterBool(ConvexParser.BoolContext ctx);
	/**
	 * Exit a parse tree produced by {@link ConvexParser#bool}.
	 * @param ctx the parse tree
	 */
	void exitBool(ConvexParser.BoolContext ctx);
	/**
	 * Enter a parse tree produced by {@link ConvexParser#character}.
	 * @param ctx the parse tree
	 */
	void enterCharacter(ConvexParser.CharacterContext ctx);
	/**
	 * Exit a parse tree produced by {@link ConvexParser#character}.
	 * @param ctx the parse tree
	 */
	void exitCharacter(ConvexParser.CharacterContext ctx);
	/**
	 * Enter a parse tree produced by {@link ConvexParser#keyword}.
	 * @param ctx the parse tree
	 */
	void enterKeyword(ConvexParser.KeywordContext ctx);
	/**
	 * Exit a parse tree produced by {@link ConvexParser#keyword}.
	 * @param ctx the parse tree
	 */
	void exitKeyword(ConvexParser.KeywordContext ctx);
	/**
	 * Enter a parse tree produced by {@link ConvexParser#symbol}.
	 * @param ctx the parse tree
	 */
	void enterSymbol(ConvexParser.SymbolContext ctx);
	/**
	 * Exit a parse tree produced by {@link ConvexParser#symbol}.
	 * @param ctx the parse tree
	 */
	void exitSymbol(ConvexParser.SymbolContext ctx);
	/**
	 * Enter a parse tree produced by {@link ConvexParser#pathSymbol}.
	 * @param ctx the parse tree
	 */
	void enterPathSymbol(ConvexParser.PathSymbolContext ctx);
	/**
	 * Exit a parse tree produced by {@link ConvexParser#pathSymbol}.
	 * @param ctx the parse tree
	 */
	void exitPathSymbol(ConvexParser.PathSymbolContext ctx);
	/**
	 * Enter a parse tree produced by {@link ConvexParser#syntax}.
	 * @param ctx the parse tree
	 */
	void enterSyntax(ConvexParser.SyntaxContext ctx);
	/**
	 * Exit a parse tree produced by {@link ConvexParser#syntax}.
	 * @param ctx the parse tree
	 */
	void exitSyntax(ConvexParser.SyntaxContext ctx);
	/**
	 * Enter a parse tree produced by {@link ConvexParser#quoted}.
	 * @param ctx the parse tree
	 */
	void enterQuoted(ConvexParser.QuotedContext ctx);
	/**
	 * Exit a parse tree produced by {@link ConvexParser#quoted}.
	 * @param ctx the parse tree
	 */
	void exitQuoted(ConvexParser.QuotedContext ctx);
	/**
	 * Enter a parse tree produced by {@link ConvexParser#string}.
	 * @param ctx the parse tree
	 */
	void enterString(ConvexParser.StringContext ctx);
	/**
	 * Exit a parse tree produced by {@link ConvexParser#string}.
	 * @param ctx the parse tree
	 */
	void exitString(ConvexParser.StringContext ctx);
	/**
	 * Enter a parse tree produced by {@link ConvexParser#commented}.
	 * @param ctx the parse tree
	 */
	void enterCommented(ConvexParser.CommentedContext ctx);
	/**
	 * Exit a parse tree produced by {@link ConvexParser#commented}.
	 * @param ctx the parse tree
	 */
	void exitCommented(ConvexParser.CommentedContext ctx);
}