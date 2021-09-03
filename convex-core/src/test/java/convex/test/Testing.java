package convex.test;

import java.io.IOException;

import convex.core.Constants;
import convex.core.data.ACell;
import convex.core.data.AList;
import convex.core.lang.Context;
import convex.core.lang.Reader;
import convex.core.util.Utils;

public class Testing {

	/**
	 * Runs all tests in a forked context
	 * @param ctx
	 * @param resourceName
	 * @return Updates context after all test are run. This will be a new fork.
	 */
	public static Context<?> runTests(Context<?> ctx, String resourceName) {
		ctx=ctx.fork();
		try {
			String source=Utils.readResourceAsString(resourceName);
			AList<ACell> forms=Reader.readAll(source);
			for (ACell form: forms) {
				ctx=ctx.eval(form);
				if (ctx.isExceptional()) {
					System.err.println("Error in form: "+form);
					return ctx;
				}
				ctx=ctx.withJuice(Constants.MAX_TRANSACTION_JUICE);
			}
			return ctx;
		} catch (IOException e) {
			throw Utils.sneakyThrow(e);
		}
	}

}
