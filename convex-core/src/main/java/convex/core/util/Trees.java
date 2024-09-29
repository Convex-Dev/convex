package convex.core.util;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Utility class for tree handling functions
 */
public class Trees {

	/**
	 * Visits elements on a stack, popping one off from the end each time. 
	 * Visitor function MAY edit the stack. Will terminate when stack is empty.
	 * 
	 * IMPORTANT: O(1) usage of JVM stack, may be necessary to use a function like this when 
	 * visiting deeply nested trees in CVM code.
	 * 
	 * @param <T> Type of element to visit
	 * @param stack Stack of values to visit, must be a mutable List
	 * @param visitor Visitor function to call for each stack element.
	 */
	public static <T> void visitStack(List<T> stack, Consumer<? super T> visitor) {
		while(!stack.isEmpty()) {
			int pos=stack.size()-1;
			T r=stack.remove(pos);
			visitor.accept(r);
		}
	}
	
	/**
	 * Visits elements on a stack.
	 * Pops the element if predicate returns true, otherwise leaves on stack for later handling.
	 * Predicate function MAY add to the stack. Will terminate when stack is empty.
	 * 
	 * IMPORTANT: O(1) usage of JVM stack, may be necessary to use a function like this when 
	 * visiting deeply nested trees in CVM code.
	 * 
	 * @param <T> Type of element to visit
	 * @param stack Stack of values to visit, must be a mutable List
	 * @param visitor Visitor function to call for each stack element.
	 */
	public static <T> void visitStackMaybePopping(List<T> stack, Predicate<? super T> visitor) {
		while(!stack.isEmpty()) {
			int pos=stack.size()-1;
			T r=stack.get(pos);
			boolean pop= visitor.test(r);
			if (pop) stack.remove(pos);
		}
	}
}
