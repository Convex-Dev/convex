package convex.core.lang;

import convex.core.data.ACell;
import convex.core.data.AList;
import convex.core.data.Address;
import convex.core.data.List;
import convex.core.data.Symbol;
import convex.core.init.Init;

/**
 * Static utilities and functions for CVM code generation
 * 
 * In general, these are helper functions which:
 * - Abstract away from complexity and specific details of code generation
 * - Are more efficient than most alternative approaches e.g. going via the Reader
 * 
 */
public class Code {

	/**
	 * Create code for a CNS update call
	 * @param name Symbol to update in CNS e.g. 'app.cool.project'
	 * @param addr Address to associate with CNS record e.g. #123
	 * @return Code for CNS call
	 */
	public static AList<ACell> cnsUpdate(Symbol name, Address addr, ACell controller) {
		AList<ACell> update=List.of(Symbols.LOOKUP,Init.REGISTRY_ADDRESS,Symbols.CREATE);
		AList<ACell> cmd=List.of(update, quote(name),addr, controller);
		return cmd;
	}
	
	/**
	 * Create code to quote an arbitrary form
	 * @param form Form to quote
	 * @return Form to produce the quoted Symbol
	 */
	public static AList<Symbol> quote(ACell form) {
		return List.of(Symbols.QUOTE, form);
	}

	/**
	 * Create code to quote a Symbol
	 * @param sym Symbol to quote
	 * @return Form to produce the quoted Symbol
	 */
	public static AList<Symbol> quote(Symbol sym) {
		return List.of(Symbols.QUOTE, sym);
	}
	

}
