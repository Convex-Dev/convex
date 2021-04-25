package convex.core.lang.expanders;

import convex.core.data.ACell;
import convex.core.data.Syntax;
import convex.core.data.Tag;
import convex.core.lang.Context;

/**
 * Abstract base class for expanders.
 * 
 * Expanders follow the model proposed in the paper: "Expansion-Passing Style: A
 * General Macro Mechanism" by R. Kent Dybvig, Daniel P. Friedman, Christopher
 * T. Haynes
 * 
 */
public abstract class AExpander extends ACell {

	/**
	 * Expands a form in the given context, delegating further expansion to the
	 * passed expander.
	 * 
	 * @param form    Form to be expanded. May be any value from reader.
	 * @param ex      Expansion function that will be applied to any form that needs
	 *                to be further expanded
	 * @param context Context of expansion execution
	 * @return Updated context with expanded form as result.
	 * @throws ExecutionException
	 */
	public abstract Context<Syntax> expand(ACell form, AExpander ex, Context<?> context);
	
	@Override public final boolean isCVMValue() {
		return true;
	}
	
	@Override
	public byte getTag() {
		return Tag.EXPANDER;
	}
}
