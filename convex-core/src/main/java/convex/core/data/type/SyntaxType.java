package convex.core.data.type;

import convex.core.cvm.Syntax;
import convex.core.data.ACell;

/**
 * Type that represents CVM Syntax Object values
 */
public final class SyntaxType extends AStandardType<Syntax> {

	/**
	 * Singleton runtime instance
	 */
	public static final SyntaxType INSTANCE = new SyntaxType();

	private SyntaxType() {
		super(Syntax.class);
	}
	
	@Override
	public boolean check(ACell value) {
		return value instanceof Syntax;
	}
	
	@Override
	public String toString () {
		return "Syntax";
	}

	@Override
	public Syntax defaultValue() {
		return Syntax.EMPTY;
	}

	@Override
	public Syntax implicitCast(ACell a) {
		if (a instanceof Syntax) return (Syntax)a;
		return null;
	}
}
