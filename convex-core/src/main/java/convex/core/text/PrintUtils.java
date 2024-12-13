package convex.core.text;

import convex.core.data.ACell;
import convex.core.data.ACountable;
import convex.core.data.Cells;
import convex.core.data.Ref;
import convex.core.lang.RT;
import convex.core.util.Utils;

public class PrintUtils {

	public static String printRefTree(Ref<?> ref) {
		StringBuilder sb=new StringBuilder();
		
		printRefTree(sb,ref,0);
		return sb.toString();
	}

	private static void printRefTree(StringBuilder sb, Ref<?> ref, int level) {


		String prefix=Text.whiteSpace(level);
		
		if (ref.isMissing()) {
			sb.append(prefix);
			sb.append("MISSING: "+ref.getHash());
			sb.append('\n');
		} else {
			ACell val=ref.getValue();
			String name=Utils.getClassName(val);
			if (Cells.isCompletelyEncoded(val)) {
				name+=" = " +RT.toString(val);
			} else {
				if (val instanceof ACountable) {
					name+=" [count=" +RT.count(val)+"]";
				}
			}
			
			
			sb.append(prefix);
			sb.append(name);
			sb.append('\n');
			
			int rc=Cells.refCount(val);
			for (int i=0; i<rc; i++) {
				printRefTree(sb,val.getRef(i),level+2);
			}
		}
	}
}
