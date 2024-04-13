package convex.dlfs;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Vectors;

/**
 * Static utility class for working with DLFS Node structures
 */
public class DLFSNode {

	static final AHashMap<AString,AVector<ACell>> EMPTY_CONTENTS = Maps.empty();
	static final AHashMap<AString,AVector<ACell>> NIL_CONTENTS = null;
	public static final AVector<ACell> EMPTY_DIRECTORY=Vectors.of(EMPTY_CONTENTS);
	public static final AVector<ACell> EMPTY_FILE=Vectors.of(NIL_CONTENTS);
	
	// node structure contents
	private static final int POS_DIR = 0;
	
	public static boolean isDirector(AVector<ACell> node) {
		return node.get(POS_DIR)!=null;
	}

}
