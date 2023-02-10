package convex.core.util;

import java.util.List;
import java.util.function.Consumer;

/**
 * Utility class for tree handling functions
 */
public class Trees {

	/**
	 * Visits elements on a stack, popping one off from the end each time. 
	 * Visitor function MAY edit the stack. Will terminate when stack is empty.
	 * 
	 * @param <T> Type of element to visit
	 * @param stack Stack of values to visit
	 * @param visitor Visitor function to call for each stack elemet.
	 */
	public static <T> void visitStack(List<T> stack, Consumer<T> visitor) {
		while(!stack.isEmpty()) {
			int pos=stack.size()-1;
			T r=stack.remove(pos);
			visitor.accept(r);
		}
	}
}
