package convex.test;

import java.io.IOException;
import java.nio.ByteBuffer;

import convex.core.cvm.Context;
import convex.core.data.ACell;
import convex.core.data.AList;
import convex.core.data.Blob;
import convex.core.lang.Reader;
import convex.core.util.Utils;

public class Testing {

	/**
	 * Runs all tests in a forked context
	 * @param ctx Context in which to execute test code
	 * @param resourceName Path to resource
	 * @return Updates context after all test are run. This will be a new fork.
	 */
	public static Context runTests(Context ctx, String resourceName) {
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
				ctx=ctx.withJuice(0);
			}
			return ctx;
		} catch (IOException e) {
			throw Utils.sneakyThrow(e);
		}
	}

	public static ByteBuffer messageBuffer(String hex) {
		Blob b=Blob.fromHex(hex);
		ByteBuffer bb = b.getByteBuffer();
		bb.position(Utils.checkedInt(b.count()));
		return bb;
	}

}
