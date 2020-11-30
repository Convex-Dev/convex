package convex.test;

import java.io.IOException;

import convex.core.lang.Context;
import convex.core.lang.TestState;
import convex.core.util.Utils;

public class Testing {

	public static Context<?> runTests(Context<?> ctx, String resourceName) {
		try {
			String source=Utils.readResourceAsString(resourceName);
			return TestState.step(ctx,source);
		} catch (IOException e) {
			throw Utils.sneakyThrow(e);
		}
	}

}
