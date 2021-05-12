package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.Symbol;
import convex.core.lang.Symbols;

/**
 * Type that represents CVM Byte values
 */
public final class SymbolType extends AType {

	/**
	 * Singleton runtime instance
	 */
	public static final SymbolType INSTANCE = new SymbolType();

	private SymbolType() {
		
	}
	
	@Override
	public boolean check(ACell value) {
		return value instanceof Symbol;
	}
	
	@Override
	public String toString () {
		return "Keyword";
	}

	@Override
	public boolean allowsNull() {
		return false;
	}

	@Override
	protected Symbol defaultValue() {
		return Symbols.FOO;
	}

	@Override
	protected Symbol implicitCast(ACell a) {
		if (a instanceof Symbol) return (Symbol)a;
		return null;
	}
	
	@Override
	protected Class<? extends ACell> getJavaClass() {
		return Symbol.class;
	}

}
