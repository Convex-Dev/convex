package convex.core.lang;

import convex.core.data.ACell;
import convex.core.data.AList;
import convex.core.data.Address;
import convex.core.data.List;
import convex.core.data.Symbol;
import convex.core.init.Init;

/**
 * Static utilities and functions for CVM code generation
 */
public class Code {

	public static AList<ACell> cnsUpdate(Symbol name, Address addr) {
		AList<ACell> cmd=List.of(Symbols.CNS_UPDATE, Code.quote(name), addr);
		return List.of(Symbols.CALL, Init.REGISTRY_ADDRESS, cmd );
	}

	private static AList<Symbol> quote(Symbol name) {
		return List.of(Symbols.QUOTE, name);
	}
}
