package convex.etch;

import java.io.IOException;

/**
 * Visitor for Etch index
 */
public interface IEtchIndexVisitor {
	public void visit(Etch e, int level, int[] digits, long indexPointer) throws IOException;
}
