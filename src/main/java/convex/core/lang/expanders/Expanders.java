package convex.core.lang.expanders;

import convex.core.data.Syntax;
import convex.core.lang.Context;

public class Expanders {

	public static AExpander IDENTITY = new BaseExpander() {

		@Override
		public Context<Syntax> expand(Object form, AExpander ex, Context<?> context) {
			if ((form instanceof Syntax)) {
				return context.withResult((Syntax) form);
			} else {
				return context.withResult(Syntax.create(form));
			}

		}

	};

}
