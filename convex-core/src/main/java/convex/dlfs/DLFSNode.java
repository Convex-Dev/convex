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
	
	public static boolean isDirectory(AVector<ACell> node) {
		if (node==null) return false;
		return node.get(POS_DIR)!=null;
	}

	@SuppressWarnings("unchecked")
	public static AVector<ACell> navigate(AVector<ACell> node, DLPath path) {
		int n=path.getNameCount();
		for (int i=0; i<n; i++) {
			AString compName=path.getCVMName(i);
			AHashMap<AString,AVector<ACell>> dir=(AHashMap<AString, AVector<ACell>>) node.get(POS_DIR);
			if (dir==null) return null;
			AVector<ACell> child=dir.get(compName);
			if (child==null) return null;
			node=child;
		}
		return node;
	}

}
