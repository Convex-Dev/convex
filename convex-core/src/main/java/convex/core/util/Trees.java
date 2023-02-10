package convex.core.util;

import java.util.List;
import java.util.function.Consumer;

/**
 * Utility class for tree handling functions
 */
public class Trees {

	public static <T> void visitStack(List<T> stack, Consumer<T> vistor) {
		while(!stack.isEmpty()) {
			int pos=stack.size()-1;
			T r=stack.remove(pos);
			vistor.accept(r);
		}
	}
}
