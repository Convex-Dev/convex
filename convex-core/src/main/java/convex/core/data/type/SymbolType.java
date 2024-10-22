package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.Symbol;
import convex.core.data.Symbols;

/**
 * Type that represents CVM Byte values
 */
public final class SymbolType extends AStandardType<Symbol> {

	/**
	 * Singleton runtime instance
	 */
	public static final SymbolType INSTANCE = new SymbolType();

	private SymbolType() {
		super(Symbol.class);
	}
	
	@Override
	public boolean check(ACell value) {
		return value instanceof Symbol;
	}
	
	@Override
	public String toString () {
		return "Symbol";
	}

	@Override
	public Symbol defaultValue() {
		return Symbols.FOO;
	}

	@Override
	public Symbol implicitCast(ACell a) {
		if (a instanceof Symbol) return (Symbol)a;
		return null;
	}
}
