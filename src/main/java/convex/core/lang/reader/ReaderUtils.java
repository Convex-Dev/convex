package convex.core.lang.reader;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AMap;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Maps;
import convex.core.data.Syntax;

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
}
