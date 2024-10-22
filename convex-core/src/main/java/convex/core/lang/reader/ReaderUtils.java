package convex.core.lang.reader;

import java.util.HashMap;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AMap;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Maps;
import convex.core.data.Symbol;
import convex.core.data.Symbols;
import convex.core.data.Syntax;
import convex.core.data.prim.CVMChar;
import convex.core.data.prim.CVMDouble;

public class ReaderUtils {

	/**
	 * Converts a metadata object according to the following rule: - Map ->
	 * unchanged - Keyword -> {:keyword true} - Any other expression -> {:tag
	 * expression}
	 * 
	 * @param metaNode Syntax node containing metadata
	 * @return Metadata map
	 */
	@SuppressWarnings("unchecked")
	public static AHashMap<ACell, ACell> interpretMetadata(ACell metaNode) {
		ACell val = Syntax.unwrapAll(metaNode);
		if (val instanceof AMap) return (AHashMap<ACell, ACell>) val;
		if (val instanceof Keyword) return Maps.of(val, Boolean.TRUE);
		return Maps.of(Keywords.TAG, val);
	}
	
	private static final HashMap<String,CVMChar> specialCharacters=Maps.hashMapOf(
			"newline",CVMChar.create('\n'),
			"space",CVMChar.create(' '),
			"tab",CVMChar.create('\t'),
			"formfeed",CVMChar.create('\f'),
			"backspace",CVMChar.create('\b'),
			"return",CVMChar.create('\r')
			);

	public static CVMChar specialCharacter(String s) {
		return specialCharacters.get(s);
	}
	
	private static final HashMap<String,Symbol> quotingSymbols=Maps.hashMapOf(
			"'",Symbols.QUOTE,
			"`",Symbols.QUASIQUOTE,
			"~",Symbols.UNQUOTE,
			"~@",Symbols.UNQUOTE_SPLICING
			);

	public static Symbol getQuotingSymbol(String s) {
		return quotingSymbols.get(s);
	}


	
	private static final HashMap<String,ACell> specialLiterals=Maps.hashMapOf(
			"##NaN",CVMDouble.NaN,
			"##Inf",CVMDouble.POSITIVE_INFINITY,
			"##-Inf",CVMDouble.NEGATIVE_INFINITY
			);

	public static ACell specialLiteral(String s) {
		return specialLiterals.get(s);
	}
	
}
