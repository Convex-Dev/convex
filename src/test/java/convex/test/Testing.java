package convex.test;

import java.io.IOException;

import convex.core.Constants;
import convex.core.data.AList;
import convex.core.lang.Context;
import convex.core.lang.Reader;
import convex.core.util.Utils;

public class Testing {

	public static Context<?> runTests(Context<?> ctx, String resourceName) {
		try {
			String source=Utils.readResourceAsString(resourceName);
			AList<Object> forms=Reader.readAll(source);
			for (Object form: forms) {
				ctx=ctx.eval(form);
				if (ctx.isExceptional()) {
					System.err.println("Error in form: "+form);
					return ctx;
				}
				ctx=ctx.setJuice(Constants.MAX_TRANSACTION_JUICE);
			}
			return ctx;
		} catch (IOException e) {
			throw Utils.sneakyThrow(e);
		}
	}

}
